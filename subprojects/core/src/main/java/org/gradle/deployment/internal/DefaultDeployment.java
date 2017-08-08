/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.deployment.internal;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.initialization.BuildGateToken;

import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultDeployment implements Deployment {
    private static final Logger LOGGER = Logging.getLogger(DefaultDeployment.class);

    private final BuildGateToken.GateKeeper gateKeeper;
    private Status status;
    private AtomicBoolean block = new AtomicBoolean();
    public static final String GATED_BUILD_SYSPROP = "org.gradle.internal.play.gated";

    DefaultDeployment(BuildGateToken buildGate) {
        if (Boolean.getBoolean(GATED_BUILD_SYSPROP)) {
            this.gateKeeper = buildGate.createGateKeeper();
        } else {
            this.gateKeeper = null;
        }
    }
    @Override
    public Status status() {
        if (gateKeeper != null) {
            LOGGER.debug("Opening gate - Play App");
            gateKeeper.open();
        }
        synchronized (block) {
            while (block.get()) {
                try {
                    block.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        return status;
    }

    @Override
    public void outOfDate() {
        synchronized (block) {
            block.set(true);
            block.notifyAll();
        }
    }

    @Override
    public void upToDate(Throwable failure) {
        status = new DefaultDeploymentStatus(failure);

        synchronized (block) {
            block.set(false);
            block.notifyAll();
        }

        if (gateKeeper != null) {
            LOGGER.debug("Closing gate - Play App");
            gateKeeper.close();
        }
    }

    private static class DefaultDeploymentStatus implements Status {
        private final Throwable failure;

        private DefaultDeploymentStatus(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public Throwable getFailure() {
            return failure;
        }
    }
}
