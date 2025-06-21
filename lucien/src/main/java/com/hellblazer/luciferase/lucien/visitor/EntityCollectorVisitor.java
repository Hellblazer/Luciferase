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
package com.hellblazer.luciferase.lucien.visitor;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Visitor that collects entities matching a given predicate.
 *
 * @param <ID>      The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * @author hal.hildebrand
 */
public class EntityCollectorVisitor<ID extends EntityID, Content> extends AbstractTreeVisitor<ID, Content> {

    private final List<EntityMatch<ID, Content>> collectedEntities = new ArrayList<>();
    private final Predicate<Content>             contentFilter;
    private final int                            maxResults;

    /**
     * Create a collector that collects all entities.
     */
    public EntityCollectorVisitor() {
        this(content -> true, Integer.MAX_VALUE);
    }

    /**
     * Create a collector with a content filter.
     *
     * @param contentFilter predicate to filter content
     */
    public EntityCollectorVisitor(Predicate<Content> contentFilter) {
        this(contentFilter, Integer.MAX_VALUE);
    }

    /**
     * Create a collector with a content filter and result limit.
     *
     * @param contentFilter predicate to filter content
     * @param maxResults    maximum number of results to collect
     */
    public EntityCollectorVisitor(Predicate<Content> contentFilter, int maxResults) {
        this.contentFilter = contentFilter;
        this.maxResults = maxResults;
        this.visitEntities = true;
    }

    /**
     * Get the collected entities.
     *
     * @return list of entity matches
     */
    public List<EntityMatch<ID, Content>> getCollectedEntities() {
        return new ArrayList<>(collectedEntities);
    }

    /**
     * Get just the content objects.
     *
     * @return list of content objects
     */
    public List<Content> getContents() {
        return collectedEntities.stream().map(EntityMatch::content).toList();
    }

    /**
     * Get just the entity IDs.
     *
     * @return list of entity IDs
     */
    public List<ID> getEntityIds() {
        return collectedEntities.stream().map(EntityMatch::entityId).toList();
    }

    /**
     * Check if the maximum results limit was reached.
     *
     * @return true if limit was reached
     */
    public boolean isLimitReached() {
        return collectedEntities.size() >= maxResults;
    }

    /**
     * Reset the collector.
     */
    public void reset() {
        collectedEntities.clear();
    }

    @Override
    public void visitEntity(ID entityId, Content content, long nodeIndex, int level) {
        if (collectedEntities.size() >= maxResults) {
            return;
        }

        if (content != null && contentFilter.test(content)) {
            collectedEntities.add(new EntityMatch<>(entityId, content, nodeIndex, level));
        }
    }

    @Override
    public boolean visitNode(com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode<ID> node, int level,
                             long parentIndex) {
        // Stop traversal if we've collected enough results
        return collectedEntities.size() < maxResults;
    }

    /**
     * Entity match record.
     */
    public record EntityMatch<ID extends EntityID, Content>(ID entityId, Content content, long nodeIndex, int level) {
    }
}
