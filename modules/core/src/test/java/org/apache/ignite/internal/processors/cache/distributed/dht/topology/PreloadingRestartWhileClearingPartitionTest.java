/*
 * Copyright 2020 GridGain Systems, Inc. and Contributors.
 *
 * Licensed under the GridGain Community Edition License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gridgain.com/products/software/community-edition/gridgain-community-edition-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.dht.topology;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.ignite.Ignite;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.cache.CacheGroupContext;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionDemander;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPreloaderAssignments;
import org.apache.ignite.internal.processors.resource.DependencyResolver;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

/**
 * Tests if a rebalancing was cancelled and restarted during partition pre-clearing caused by full rebalancing.
 */
public class PreloadingRestartWhileClearingPartitionTest extends GridCommonAbstractTest {
    /**
     * {@inheritDoc}
     */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setConsistentId(igniteInstanceName);

        DataStorageConfiguration dsCfg = new DataStorageConfiguration().setWalSegmentSize(4 * 1024 * 1024);
        dsCfg.getDefaultDataRegionConfiguration().setPersistenceEnabled(true).setMaxSize(200 * 1024 * 1024);
        cfg.setDataStorageConfiguration(dsCfg);

        cfg.setCacheConfiguration(new CacheConfiguration(DEFAULT_CACHE_NAME).
            setBackups(2).
            setAffinity(new RendezvousAffinityFunction(false, 64)));

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        cleanPersistenceDir();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        stopAllGrids();

        cleanPersistenceDir();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testPreloadingRestart() throws Exception {
        IgniteEx crd = startGrids(3);

        crd.cluster().state(ClusterState.ACTIVE);

        final int clearingPart = 0;

        final int cnt = 1_100;
        final int delta = 2_000;
        final int rmv = 1_500;

        loadDataToPartition(clearingPart, getTestIgniteInstanceName(0), DEFAULT_CACHE_NAME, cnt, 0, 3);

        forceCheckpoint();

        stopGrid(2);

        loadDataToPartition(clearingPart, getTestIgniteInstanceName(0), DEFAULT_CACHE_NAME, delta, cnt, 3);

        // Removal required for triggering full rebalancing.
        List<Integer> clearKeys = partitionKeys(grid(0).cache(DEFAULT_CACHE_NAME), clearingPart, rmv, cnt);

        for (Integer clearKey : clearKeys)
            grid(0).cache(DEFAULT_CACHE_NAME).remove(clearKey);

        CountDownLatch lock = new CountDownLatch(1);
        CountDownLatch unlock = new CountDownLatch(1);

        // Start node and delay preloading in the middle of partition clearing.
        IgniteEx g2 = startGrid(2, new DependencyResolver() {
            @Override public <T> T resolve(T instance) {
                if (instance instanceof GridDhtPartitionTopologyImpl) {
                    GridDhtPartitionTopologyImpl top = (GridDhtPartitionTopologyImpl) instance;

                    top.partitionFactory(new GridDhtPartitionTopologyImpl.PartitionFactory() {
                        @Override public GridDhtLocalPartition create(GridCacheSharedContext ctx, CacheGroupContext grp, int id, boolean recovery) {
                            return id == clearingPart ?
                                new GridDhtLocalPartitionSyncEviction(ctx, grp, id, recovery, 1, lock, unlock) :
                                new GridDhtLocalPartition(ctx, grp, id, recovery);
                        }
                    });
                }

                return instance;
            }
        });

        assertTrue(U.await(lock, GridDhtLocalPartitionSyncEviction.TIMEOUT, TimeUnit.MILLISECONDS));

        // Stop supplier for clearingPart.
        GridCacheContext<Object, Object> ctx = g2.cachex(DEFAULT_CACHE_NAME).context();
        GridDhtPartitionDemander.RebalanceFuture rebFut =
            (GridDhtPartitionDemander.RebalanceFuture) ctx.preloader().rebalanceFuture();

        GridDhtPreloaderAssignments assignments = U.field(rebFut, "assignments");

        ClusterNode supplier = assignments.supplier(clearingPart);

        GridFutureAdapter clearFut = U.field(ctx.topology().localPartition(clearingPart), "clearFut");

        assertFalse(clearFut.isDone());

        grid(supplier).close();

        doSleep(1000);

        unlock.countDown();

        awaitPartitionMapExchange(true, true, null);

        assertPartitionsSame(idleVerify(grid(2), DEFAULT_CACHE_NAME));

        for (Ignite grid : G.allGrids())
            assertEquals(cnt + delta - rmv, grid.cache(DEFAULT_CACHE_NAME).size());
    }
}