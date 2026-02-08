/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.transport;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.fireflies.FirefliesMembershipView;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test utility for creating SocketTransport instances with mocked Fireflies components.
 * <p>
 * Centralizes the complexity of creating Delos View mocks for testing.
 * All tests that need SocketTransport should use this factory.
 *
 * @author hal.hildebrand
 */
public class TestTransportFactory {

    /**
     * Create a SocketTransport for testing with mocked Fireflies components.
     *
     * @param localId   UUID for this transport
     * @param myAddress Process address
     * @return SocketTransport with stable mocked Fireflies infrastructure
     */
    @SuppressWarnings("unchecked")
    public static SocketTransport createTestTransport(UUID localId, ProcessAddress myAddress) {
        // Create mocked Fireflies membership view
        var mockView = mock(View.class);
        var mockContext = mock(DynamicContext.class);
        when(mockView.getContext()).thenReturn((DynamicContext) mockContext);
        when(mockContext.getId()).thenReturn(Digest.NONE);  // Stable view ID

        var membership = new FirefliesMembershipView(mockView);

        // Create view monitor with MockFirefliesView
        var viewMonitor = new FirefliesViewMonitor(new MockFirefliesView<>());

        // Create RealTimeController
        var controller = new RealTimeController(localId, "test-transport", 100);  // 100 Hz

        return new SocketTransport(localId, myAddress, membership, viewMonitor, controller);
    }

    /**
     * Create a SocketTransport with a random local ID.
     *
     * @param myAddress Process address
     * @return SocketTransport with random UUID and mocked Fire flies infrastructure
     */
    public static SocketTransport createTestTransport(ProcessAddress myAddress) {
        return createTestTransport(UUID.randomUUID(), myAddress);
    }
}
