/*
 * Copyright 2021-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.pipelines.fabric.impl.behaviour.upf;

import org.onlab.packet.Ip4Address;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.TestConsistentMap;
import org.onosproject.store.service.TestDistributedSet;

import java.util.Set;

import static org.onosproject.pipelines.fabric.impl.behaviour.upf.DistributedFabricUpfStore.BUFFER_FAR_ID_SET_NAME;
import static org.onosproject.pipelines.fabric.impl.behaviour.upf.DistributedFabricUpfStore.FAR_ID_MAP_NAME;
import static org.onosproject.pipelines.fabric.impl.behaviour.upf.DistributedFabricUpfStore.FAR_ID_UE_MAP_NAME;
import static org.onosproject.pipelines.fabric.impl.behaviour.upf.DistributedFabricUpfStore.SERIALIZER;


public final class TestDistributedFabricUpfStore {

    private TestDistributedFabricUpfStore() {
    }

    public static DistributedFabricUpfStore build() {
        var store = new DistributedFabricUpfStore();
        TestConsistentMap.Builder<UpfRuleIdentifier, Integer> farIdMapBuilder =
                TestConsistentMap.builder();
        farIdMapBuilder.withName(FAR_ID_MAP_NAME)
                .withRelaxedReadConsistency()
                .withSerializer(Serializer.using(SERIALIZER.build()));
        store.farIdMap = farIdMapBuilder.build();

        TestDistributedSet.Builder<UpfRuleIdentifier> bufferFarIdsBuilder =
                TestDistributedSet.builder();
        bufferFarIdsBuilder
                .withName(BUFFER_FAR_ID_SET_NAME)
                .withRelaxedReadConsistency()
                .withSerializer(Serializer.using(SERIALIZER.build()));
        store.bufferFarIds = bufferFarIdsBuilder.build().asDistributedSet();

        TestConsistentMap.Builder<UpfRuleIdentifier, Set<Ip4Address>> farIdToUeAddrsBuilder =
                TestConsistentMap.builder();
        farIdToUeAddrsBuilder
                .withName(FAR_ID_UE_MAP_NAME)
                .withRelaxedReadConsistency()
                .withSerializer(Serializer.using(SERIALIZER.build()));
        store.farIdToUeAddrs = farIdToUeAddrsBuilder.build();

        store.activate();

        // Init with some translation state.
        store.farIdMap.put(
                new UpfRuleIdentifier(TestUpfConstants.SESSION_ID, TestUpfConstants.UPLINK_FAR_ID),
                TestUpfConstants.UPLINK_PHYSICAL_FAR_ID);
        store.farIdMap.put(
                new UpfRuleIdentifier(TestUpfConstants.SESSION_ID, TestUpfConstants.DOWNLINK_FAR_ID),
                TestUpfConstants.DOWNLINK_PHYSICAL_FAR_ID);

        return store;
    }
}