/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.qos;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.jmock.lib.concurrent.DeterministicScheduler;
import org.junit.Before;
import org.junit.Test;

public class AtlasDbQosClientTest {
    private QosService qosService = mock(QosService.class);
    private DeterministicScheduler scheduler = new DeterministicScheduler();

    @Before
    public void setUp() {
        when(qosService.getLimit("test-client")).thenReturn(1L);
    }

    @Test
    public void doesNotBackOff() {
        AtlasDbQosClient qosClient = new AtlasDbQosClient(qosService, scheduler, "test-client");
        scheduler.tick(1L, TimeUnit.MILLISECONDS);
        qosClient.checkLimit();
    }

    @Test
    public void throwsAfterLimitExceeded() {
        AtlasDbQosClient qosClient = new AtlasDbQosClient(qosService, scheduler, "test-client");
        scheduler.tick(1L, TimeUnit.MILLISECONDS);
        qosClient.checkLimit();

        assertThatThrownBy(qosClient::checkLimit).isInstanceOf(RuntimeException.class);
    }

    @Test
    public void canCheckAgainAfterRefreshPeriod() {
        AtlasDbQosClient qosClient = new AtlasDbQosClient(qosService, scheduler, "test-client");
        scheduler.tick(1L, TimeUnit.MILLISECONDS);
        qosClient.checkLimit();

        assertThatThrownBy(qosClient::checkLimit)
                .isInstanceOf(RuntimeException.class).hasMessage("Rate limit exceeded");

        scheduler.tick(60L, TimeUnit.SECONDS);

        qosClient.checkLimit();
    }
}