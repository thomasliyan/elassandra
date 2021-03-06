/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.node;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.elassandra.discovery.CassandraDiscoveryModule;
import org.elassandra.gateway.CassandraGatewayModule;
import org.elassandra.gateway.CassandraGatewayService;
import org.elassandra.indices.CassandraIndicesClusterStateService;
import org.elasticsearch.Build;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.cache.recycler.PageCacheRecycler;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClientModule;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.ClusterNameModule;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.StopWatch;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.discovery.DiscoveryService;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.EnvironmentModule;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.env.NodeEnvironmentModule;
import org.elasticsearch.http.HttpServer;
import org.elasticsearch.http.HttpServerModule;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.index.search.shape.ShapeModule;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.breaker.CircuitBreakerModule;
import org.elasticsearch.indices.cache.query.IndicesQueryCache;
import org.elasticsearch.indices.fielddata.cache.IndicesFieldDataCache;
import org.elasticsearch.indices.memory.IndexingMemoryController;
import org.elasticsearch.indices.store.IndicesStore;
import org.elasticsearch.monitor.MonitorModule;
import org.elasticsearch.monitor.MonitorService;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.node.internal.InternalSettingsPreparer;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.percolator.PercolatorModule;
import org.elasticsearch.percolator.PercolatorService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.PluginsModule;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.repositories.RepositoriesModule;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestModule;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolModule;
import org.elasticsearch.transport.TransportModule;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.tribe.TribeModule;
import org.elasticsearch.tribe.TribeService;
import org.elasticsearch.watcher.ResourceWatcherModule;
import org.elasticsearch.watcher.ResourceWatcherService;

/**
 * A node represent a node within a cluster (<tt>cluster.name</tt>). The {@link #client()} can be used
 * in order to use a {@link Client} to perform actions/operations against the cluster.
 * <p/>
 * <p>In order to create a node, the {@link NodeBuilder} can be used. When done with it, make sure to
 * call {@link #close()} on it.
 */
public class Node implements Releasable {

    private static final String CLIENT_TYPE = "node";
    public static final String HTTP_ENABLED = "http.enabled";
    private final Lifecycle lifecycle = new Lifecycle();
    private final Injector injector;
    private final Settings settings;
    private final Environment environment;
    private final NodeEnvironment nodeEnvironment;
    private final PluginsService pluginsService;
    private final Client client;
    
    private ClusterService clusterService = null;
    private CassandraGatewayService gatewayService = null;
    
    /**
     * Constructs a node with the given settings.
     *
     * @param preparedSettings Base settings to configure the node with
     */
    public Node(Settings preparedSettings) {
        this(InternalSettingsPreparer.prepareEnvironment(preparedSettings, null), Version.CURRENT, Collections.<Class<? extends Plugin>>emptyList());
    }

    public Node(Settings preparedSettings, Collection<Class<? extends Plugin>> classpathPlugins) {
        this(InternalSettingsPreparer.prepareEnvironment(preparedSettings, null), Version.CURRENT, classpathPlugins);
    }
    
    protected Node(Environment tmpEnv, Version version, Collection<Class<? extends Plugin>> classpathPlugins) {
        Settings tmpSettings = settingsBuilder().put(tmpEnv.settings())
            .put(Client.CLIENT_TYPE_SETTING, CLIENT_TYPE).build();
        tmpSettings = TribeService.processSettings(tmpSettings);

        ESLogger logger = Loggers.getLogger(Node.class, tmpSettings.get("name"));
        logger.info("version[{}], pid[{}], build[{}/{}]", version, JvmInfo.jvmInfo().pid(), Build.CURRENT.hashShort(), Build.CURRENT.timestamp());

        logger.info("initializing ...");

        if (logger.isDebugEnabled()) {
            logger.debug("using config [{}], data [{}], logs [{}], plugins [{}]",
                tmpEnv.configFile(), Arrays.toString(tmpEnv.dataFiles()), tmpEnv.logsFile(), tmpEnv.pluginsFile());
        }

        this.pluginsService = new PluginsService(tmpSettings, tmpEnv.modulesFile(), tmpEnv.pluginsFile(), classpathPlugins);
        this.settings = pluginsService.updatedSettings();
        // create the environment based on the finalized (processed) view of the settings
        this.environment = new Environment(this.settings());

        try {
            nodeEnvironment = new NodeEnvironment(this.settings, this.environment);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to created node environment", ex);
        }

        final ThreadPool threadPool = new ThreadPool(settings);
        NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry();

        boolean success = false;
        try {
            ModulesBuilder modules = new ModulesBuilder();
            modules.add(new Version.Module(version));
            modules.add(new CircuitBreakerModule(settings));
            // plugin modules must be added here, before others or we can get crazy injection errors...
            for (Module pluginModule : pluginsService.nodeModules()) {
                modules.add(pluginModule);
            }
            modules.add(new PluginsModule(pluginsService));
            modules.add(new SettingsModule(this.settings));
            modules.add(new NodeModule(this));
            modules.add(new NetworkModule(namedWriteableRegistry));
            modules.add(new ScriptModule(this.settings));
            modules.add(new EnvironmentModule(environment));
            modules.add(new NodeEnvironmentModule(nodeEnvironment));
            modules.add(new ClusterNameModule(this.settings));
            modules.add(new ThreadPoolModule(threadPool));
            modules.add(new CassandraDiscoveryModule(this.settings));
            modules.add(new ClusterModule(this.settings));
            modules.add(new RestModule(this.settings));
            modules.add(new TransportModule(settings, namedWriteableRegistry));
            if (settings.getAsBoolean(HTTP_ENABLED, true)) {
                modules.add(new HttpServerModule(settings));
            }
            modules.add(new IndicesModule());
            modules.add(new SearchModule());
            modules.add(new ActionModule(false));
            modules.add(new MonitorModule(settings));
            modules.add(new CassandraGatewayModule(settings));
            modules.add(new NodeClientModule());
            modules.add(new ShapeModule());
            modules.add(new PercolatorModule());
            modules.add(new ResourceWatcherModule());
            //modules.add(new RepositoriesModule());
            modules.add(new TribeModule());


            pluginsService.processModules(modules);

            injector = modules.createInjector();

            client = injector.getInstance(Client.class);
            threadPool.setNodeSettingsService(injector.getInstance(NodeSettingsService.class));
            success = true;
        } finally {
            if (!success) {
                nodeEnvironment.close();
                ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
            }
        }

        logger.info("initialized");
    }
    
    public NodeEnvironment nodeEnvironment() {
        return this.nodeEnvironment;
    }

    /**
     * The settings that were used to create the node.
     */
    public Settings settings() {
        return this.settings;
    }

    /**
     * A client that can be used to execute actions (operations) against the cluster.
     */
    public Client client() {
        return client;
    }

    public synchronized ClusterService clusterService() {
         if (this.clusterService == null)
              this.clusterService = injector.getInstance(ClusterService.class);
         return this.clusterService;
    }
    
    public synchronized CassandraGatewayService gatewayService() {
         if (this.gatewayService == null)
              this.gatewayService = injector.getInstance(CassandraGatewayService.class);
         return this.gatewayService;
    }
    
    /**
     * Start Elasticsearch for write-only operations.
     * @return
     */
    public Node activate() {
        if (!lifecycle.moveToStarted()) {
            return this;
        }
        ESLogger logger = Loggers.getLogger(Node.class, settings.get("name"));
        logger.info("activating ...");

        // hack around dependency injection problem (for now...)
        //injector.getInstance(Discovery.class).setAllocationService(injector.getInstance(AllocationService.class));
        injector.getInstance(TransportService.class).start();

        clusterService().start();

        IndicesService indiceService = injector.getInstance(IndicesService.class);
        indiceService.start();
        injector.getInstance(IndexingMemoryController.class).start();
        injector.getInstance(CassandraIndicesClusterStateService.class).start();

        // gateway should start after disco, so it can try and recovery from gateway on "start"
        gatewayService().start(); // block until recovery done from cassandra schema.

        logger.info("activated ...");
        return this;
    }
    
    /**
     * finish ElasticSearch start when we have joined the ring.
     */
    @SuppressForbidden(reason = "System#out")
    public Node start() {
        ESLogger logger = Loggers.getLogger(Node.class, settings.get("name"));
        logger.info("starting ...");

        for (Class<? extends LifecycleComponent> plugin : pluginsService.nodeServices()) {
            injector.getInstance(plugin).start();
        }

        injector.getInstance(DiscoveryService.class).start(); // should not start before cassandra boostraps is finished.
        //injector.getInstance(IndicesTTLService.class).start();
        //injector.getInstance(SnapshotsService.class).start();
        //injector.getInstance(SnapshotShardsService.class).start();
        injector.getInstance(SearchService.class).start();
        injector.getInstance(MonitorService.class).start();
        injector.getInstance(RestController.class).start();

        // TODO hack around circular dependencies problems
        //injector.getInstance(GatewayAllocator.class).setReallocation(injector.getInstance(ClusterService.class), injector.getInstance(RoutingService.class));

        injector.getInstance(ResourceWatcherService.class).start();
       // injector.getInstance(GatewayService.class).start();

        // Start the transport service now so the publish address will be added to the local disco node in ClusterService
        TransportService transportService = injector.getInstance(TransportService.class);
        transportService.start();
        injector.getInstance(ClusterService.class).start();

        // start after cluster service so the local disco is known
        //DiscoveryService discoService = injector.getInstance(DiscoveryService.class).start();

        transportService.acceptIncomingRequests();
        
        if (settings.getAsBoolean("http.enabled", true)) {
            injector.getInstance(HttpServer.class).start();
        }
        injector.getInstance(TribeService.class).start();
        if (settings.getAsBoolean("node.portsfile", false)) {
            if (settings.getAsBoolean("http.enabled", true)) {
                HttpServerTransport http = injector.getInstance(HttpServerTransport.class);
                writePortsFile("http", http.boundAddress());
            }
            TransportService transport = injector.getInstance(TransportService.class);
            writePortsFile("transport", transport.boundAddress());
        }

        // create elastic_admin if not exists after joining the ring and before allowing metadata update.
        clusterService().createOrUpdateElasticAdminKeyspace();
        
        // publish X1+X2 gossip states
        clusterService().publishGossipStates();
        
        // Cassandra started => release metadata update blocks.
        gatewayService().enableMetaDataPersictency();
        
        logger.info("Elasticsearch started state={}", clusterService.state().toString());
        
        // Added for esrally when started in foreground.
        System.out.println("Elassandra started"); 
        return this;
    }

    private Node stop() {
        if (!lifecycle.moveToStopped()) {
            return this;
        }
        ESLogger logger = Loggers.getLogger(Node.class, settings.get("name"));
        logger.info("stopping ...");

        injector.getInstance(TribeService.class).stop();
        injector.getInstance(ResourceWatcherService.class).stop();
        if (settings.getAsBoolean("http.enabled", true)) {
            injector.getInstance(HttpServer.class).stop();
        }

        //injector.getInstance(SnapshotsService.class).stop();
        //injector.getInstance(SnapshotShardsService.class).stop();
        // stop any changes happening as a result of cluster state changes
        injector.getInstance(CassandraIndicesClusterStateService.class).stop();
        // we close indices first, so operations won't be allowed on it
        injector.getInstance(IndexingMemoryController.class).stop();
        //injector.getInstance(IndicesTTLService.class).stop();
        //injector.getInstance(RoutingService.class).stop();
        injector.getInstance(ClusterService.class).stop();
        injector.getInstance(DiscoveryService.class).stop();
        injector.getInstance(MonitorService.class).stop();
        injector.getInstance(CassandraGatewayService.class).stop();
        injector.getInstance(SearchService.class).stop();
        injector.getInstance(RestController.class).stop();
        injector.getInstance(TransportService.class).stop();

        for (Class<? extends LifecycleComponent> plugin : pluginsService.nodeServices()) {
            injector.getInstance(plugin).stop();
        }
        // we should stop this last since it waits for resources to get released
        // if we had scroll searchers etc or recovery going on we wait for to finish.
        injector.getInstance(IndicesService.class).stop();
        logger.info("stopped");

        return this;
    }

    // During concurrent close() calls we want to make sure that all of them return after the node has completed it's shutdown cycle.
    // If not, the hook that is added in Bootstrap#setup() will be useless: close() might not be executed, in case another (for example api) call
    // to close() has already set some lifecycles to stopped. In this case the process will be terminated even if the first call to close() has not finished yet.
    @Override
    public synchronized void close() {
        if (lifecycle.started()) {
            stop();
        }
        if (!lifecycle.moveToClosed()) {
            return;
        }

        ESLogger logger = Loggers.getLogger(Node.class, settings.get("name"));
        logger.info("closing ...");

        StopWatch stopWatch = new StopWatch("node_close");
        stopWatch.start("tribe");
        injector.getInstance(TribeService.class).close();
        stopWatch.stop().start("http");
        if (settings.getAsBoolean("http.enabled", true)) {
            injector.getInstance(HttpServer.class).close();
        }
        //stopWatch.stop().start("snapshot_service");
        //injector.getInstance(SnapshotsService.class).close();
        //injector.getInstance(SnapshotShardsService.class).close();
        stopWatch.stop().start("client");
        Releasables.close(injector.getInstance(Client.class));
        stopWatch.stop().start("indices_cluster");
        injector.getInstance(CassandraIndicesClusterStateService.class).close();
        stopWatch.stop().start("indices");
        injector.getInstance(IndexingMemoryController.class).close();
        //injector.getInstance(IndicesTTLService.class).close();
        injector.getInstance(IndicesService.class).close();
        // close filter/fielddata caches after indices
        injector.getInstance(IndicesQueryCache.class).close();
        injector.getInstance(IndicesFieldDataCache.class).close();
        injector.getInstance(IndicesStore.class).close();
        //stopWatch.stop().start("routing");
        //injector.getInstance(RoutingService.class).close();
        stopWatch.stop().start("cluster");
        injector.getInstance(ClusterService.class).close();
        stopWatch.stop().start("discovery");
        injector.getInstance(DiscoveryService.class).close();
        stopWatch.stop().start("monitor");
        injector.getInstance(MonitorService.class).close();
        stopWatch.stop().start("gateway");
        injector.getInstance(CassandraGatewayService.class).close();
        stopWatch.stop().start("search");
        injector.getInstance(SearchService.class).close();
        stopWatch.stop().start("rest");
        injector.getInstance(RestController.class).close();
        stopWatch.stop().start("transport");
        injector.getInstance(TransportService.class).close();
        stopWatch.stop().start("percolator_service");
        injector.getInstance(PercolatorService.class).close();

        for (Class<? extends LifecycleComponent> plugin : pluginsService.nodeServices()) {
            stopWatch.stop().start("plugin(" + plugin.getName() + ")");
            injector.getInstance(plugin).close();
        }

        stopWatch.stop().start("script");
        try {
            injector.getInstance(ScriptService.class).close();
        } catch(IOException e) {
            logger.warn("ScriptService close failed", e);
        }

        stopWatch.stop().start("thread_pool");
        // TODO this should really use ThreadPool.terminate()
        injector.getInstance(ThreadPool.class).shutdown();
        try {
            injector.getInstance(ThreadPool.class).awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
        stopWatch.stop().start("thread_pool_force_shutdown");
        try {
            injector.getInstance(ThreadPool.class).shutdownNow();
        } catch (Exception e) {
            // ignore
        }
        stopWatch.stop();

        if (logger.isTraceEnabled()) {
            logger.trace("Close times for each service:\n{}", stopWatch.prettyPrint());
        }

        injector.getInstance(NodeEnvironment.class).close();
        injector.getInstance(PageCacheRecycler.class).close();
        this.nodeEnvironment.close();
        
        logger.info("closed");
    }


    /**
     * Returns <tt>true</tt> if the node is closed.
     */
    public boolean isClosed() {
        return lifecycle.closed();
    }

    public Injector injector() {
        return this.injector;
    }

    /** Writes a file to the logs dir containing the ports for the given transport type */
    private void writePortsFile(String type, BoundTransportAddress boundAddress) {
        Path tmpPortsFile = environment.logsFile().resolve(type + ".ports.tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmpPortsFile, Charset.forName("UTF-8"))) {
            for (TransportAddress address : boundAddress.boundAddresses()) {
                InetAddress inetAddress = InetAddress.getByName(address.getAddress());
                if (inetAddress instanceof Inet6Address && inetAddress.isLinkLocalAddress()) {
                    // no link local, just causes problems
                    continue;
                }
                writer.write(NetworkAddress.format(new InetSocketAddress(inetAddress, address.getPort())) + "\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write ports file", e);
        }
        Path portsFile = environment.logsFile().resolve(type + ".ports");
        try {
            Files.move(tmpPortsFile, portsFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to rename ports file", e);
        }
    }
}
