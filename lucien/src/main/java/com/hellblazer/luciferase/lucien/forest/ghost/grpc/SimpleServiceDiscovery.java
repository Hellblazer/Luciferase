/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Simple implementation of service discovery using a static configuration.
 * 
 * This implementation maintains a static mapping of process ranks to
 * gRPC endpoints. For production use, this should be replaced with
 * a more sophisticated discovery mechanism (e.g., Consul, etcd, etc.).
 * 
 * @author Hal Hildebrand
 */
public class SimpleServiceDiscovery implements GhostServiceClient.ServiceDiscovery {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleServiceDiscovery.class);
    
    private final Map<Integer, String> endpoints;
    private final String baseHost;
    private final int basePort;
    
    /**
     * Creates a service discovery using a static host and sequential ports.
     * 
     * @param baseHost the base hostname (e.g., "localhost")
     * @param basePort the base port number (rank 0 uses this port, rank 1 uses basePort+1, etc.)
     */
    public SimpleServiceDiscovery(String baseHost, int basePort) {
        this.baseHost = Objects.requireNonNull(baseHost, "Base host cannot be null");
        this.basePort = basePort;
        this.endpoints = new ConcurrentHashMap<>();
        
        log.info("SimpleServiceDiscovery initialized with base {}:{}", baseHost, basePort);
    }
    
    /**
     * Creates a service discovery with explicit endpoint mappings.
     * 
     * @param endpoints map of rank to endpoint
     */
    public SimpleServiceDiscovery(Map<Integer, String> endpoints) {
        this.endpoints = new ConcurrentHashMap<>(Objects.requireNonNull(endpoints));
        this.baseHost = null;
        this.basePort = 0;
        
        log.info("SimpleServiceDiscovery initialized with {} explicit endpoints", endpoints.size());
    }
    
    @Override
    public String getEndpoint(int rank) {
        var endpoint = endpoints.get(rank);
        if (endpoint != null) {
            return endpoint;
        }
        
        // If using base host/port pattern, generate endpoint
        if (baseHost != null) {
            endpoint = baseHost + ":" + (basePort + rank);
            endpoints.put(rank, endpoint); // Cache for future use
            log.debug("Generated endpoint for rank {}: {}", rank, endpoint);
            return endpoint;
        }
        
        log.warn("No endpoint found for rank {}", rank);
        return null;
    }
    
    @Override
    public void registerEndpoint(int rank, String endpoint) {
        Objects.requireNonNull(endpoint, "Endpoint cannot be null");
        
        var previous = endpoints.put(rank, endpoint);
        if (previous != null && !previous.equals(endpoint)) {
            log.info("Updated endpoint for rank {} from {} to {}", rank, previous, endpoint);
        } else {
            log.info("Registered endpoint for rank {}: {}", rank, endpoint);
        }
    }
    
    @Override
    public Map<Integer, String> getAllEndpoints() {
        return Map.copyOf(endpoints);
    }
    
    /**
     * Adds multiple endpoints at once.
     * 
     * @param newEndpoints map of rank to endpoint
     */
    public void addEndpoints(Map<Integer, String> newEndpoints) {
        Objects.requireNonNull(newEndpoints, "Endpoints cannot be null");
        
        newEndpoints.forEach(this::registerEndpoint);
        log.info("Added {} endpoints", newEndpoints.size());
    }
    
    /**
     * Removes an endpoint for a specific rank.
     * 
     * @param rank the process rank
     * @return the removed endpoint, or null if none was registered
     */
    public String removeEndpoint(int rank) {
        var removed = endpoints.remove(rank);
        if (removed != null) {
            log.info("Removed endpoint for rank {}: {}", rank, removed);
        }
        return removed;
    }
    
    /**
     * Clears all registered endpoints.
     */
    public void clear() {
        int count = endpoints.size();
        endpoints.clear();
        log.info("Cleared {} endpoints", count);
    }
    
    /**
     * Gets the number of registered endpoints.
     * 
     * @return the endpoint count
     */
    public int getEndpointCount() {
        return endpoints.size();
    }
    
    /**
     * Checks if an endpoint is registered for the specified rank.
     * 
     * @param rank the process rank
     * @return true if an endpoint is registered, false otherwise
     */
    public boolean hasEndpoint(int rank) {
        return endpoints.containsKey(rank) || (baseHost != null);
    }
    
    /**
     * Creates a service discovery for local testing with sequential ports.
     * 
     * @param numProcesses the number of processes
     * @param basePort the starting port number
     * @return configured service discovery
     */
    public static SimpleServiceDiscovery forLocalTesting(int numProcesses, int basePort) {
        var discovery = new SimpleServiceDiscovery("localhost", basePort);
        
        // Pre-register all endpoints for better logging
        for (int rank = 0; rank < numProcesses; rank++) {
            discovery.registerEndpoint(rank, "localhost:" + (basePort + rank));
        }
        
        return discovery;
    }
    
    /**
     * Creates a service discovery for distributed deployment.
     * 
     * @param hosts array of hostnames, one per process rank
     * @param port the port number (same for all hosts)
     * @return configured service discovery
     */
    public static SimpleServiceDiscovery forDistributedDeployment(String[] hosts, int port) {
        var endpoints = new ConcurrentHashMap<Integer, String>();
        
        for (int rank = 0; rank < hosts.length; rank++) {
            endpoints.put(rank, hosts[rank] + ":" + port);
        }
        
        return new SimpleServiceDiscovery(endpoints);
    }
    
    @Override
    public String toString() {
        return String.format("SimpleServiceDiscovery[endpoints=%d, baseHost=%s, basePort=%d]",
                           endpoints.size(), baseHost, basePort);
    }
}