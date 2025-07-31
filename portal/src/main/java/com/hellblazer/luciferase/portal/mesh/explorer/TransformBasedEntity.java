package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Affine;

import javax.vecmath.Point3f;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Transform-based entity visualization system that reuses mesh instances.
 * Provides massive memory savings by sharing geometry across all entities.
 */
public class TransformBasedEntity {
    
    /**
     * Entity state for tracking visual properties.
     */
    public static class EntityState {
        public final Point3f position;
        public final Color color;
        public final boolean selected;
        public final boolean hasContainer;
        public final double scale;
        
        public EntityState(Point3f position, Color color, boolean selected, boolean hasContainer, double scale) {
            this.position = position;
            this.color = color;
            this.selected = selected;
            this.hasContainer = hasContainer;
            this.scale = scale;
        }
        
        public String getMaterialKey() {
            // Create unique key for material based on visual state
            return String.format("entity_%s_%b_%b", 
                color.toString(), selected, hasContainer);
        }
    }
    
    /**
     * Pool for reusing entity mesh instances.
     */
    public static class EntityPool {
        private final PrimitiveTransformManager transformManager;
        private final MaterialPool materialPool;
        private final ConcurrentLinkedQueue<MeshView> availableInstances;
        private final ConcurrentHashMap<Object, MeshView> activeInstances;
        private final int maxPoolSize;
        private int totalCreated = 0;
        
        public EntityPool(PrimitiveTransformManager transformManager, MaterialPool materialPool, int maxPoolSize) {
            this.transformManager = transformManager;
            this.materialPool = materialPool;
            this.availableInstances = new ConcurrentLinkedQueue<>();
            this.activeInstances = new ConcurrentHashMap<>();
            this.maxPoolSize = maxPoolSize;
        }
        
        /**
         * Get or create an entity instance.
         */
        public MeshView getInstance(Object entityId, EntityState state) {
            // Check if already active
            MeshView existing = activeInstances.get(entityId);
            if (existing != null) {
                updateInstance(existing, state);
                return existing;
            }
            
            // Get from pool or create new
            MeshView instance = availableInstances.poll();
            if (instance == null) {
                instance = createNewInstance();
                totalCreated++;
            }
            
            // Configure and activate
            updateInstance(instance, state);
            instance.setUserData(entityId);
            activeInstances.put(entityId, instance);
            
            return instance;
        }
        
        /**
         * Return an instance to the pool.
         */
        public void returnInstance(Object entityId) {
            MeshView instance = activeInstances.remove(entityId);
            if (instance != null && availableInstances.size() < maxPoolSize) {
                instance.setVisible(false);
                availableInstances.offer(instance);
            }
        }
        
        /**
         * Update instance visual state.
         */
        private void updateInstance(MeshView instance, EntityState state) {
            // Update transform
            Affine transform = new Affine();
            transform.setTx(state.position.x);
            transform.setTy(state.position.y);
            transform.setTz(state.position.z);
            transform.setMxx(state.scale);
            transform.setMyy(state.scale);
            transform.setMzz(state.scale);
            instance.getTransforms().setAll(transform);
            
            // Update material
            Color materialColor;
            boolean isSelected = state.selected;
            
            if (state.selected) {
                materialColor = Color.YELLOW;
            } else if (!state.hasContainer) {
                materialColor = Color.RED;
            } else {
                materialColor = state.color;
            }
            
            PhongMaterial material = materialPool.getMaterial(materialColor, 1.0, isSelected);
            instance.setMaterial(material);
            instance.setVisible(true);
        }
        
        /**
         * Create a new entity instance.
         */
        private MeshView createNewInstance() {
            // Create a sphere at origin with unit size - transform will be applied later
            PhongMaterial defaultMaterial = new PhongMaterial(Color.GRAY);
            return transformManager.createSphere(new Point3f(0, 0, 0), 0.5f, defaultMaterial);
        }
        
        /**
         * Get pool statistics.
         */
        public String getStatistics() {
            return String.format("EntityPool[total=%d, active=%d, available=%d]",
                totalCreated, activeInstances.size(), availableInstances.size());
        }
        
        /**
         * Clear all instances.
         */
        public void clear() {
            activeInstances.clear();
            availableInstances.clear();
        }
    }
    
    /**
     * Manager for transform-based entities in a scene.
     */
    public static class EntityManager {
        private final Group entityGroup;
        private final EntityPool entityPool;
        private final PrimitiveTransformManager transformManager;
        private final double defaultEntityScale;
        
        public EntityManager(PrimitiveTransformManager transformManager, MaterialPool materialPool, 
                           double defaultEntityScale, int maxPoolSize) {
            this.transformManager = transformManager;
            this.entityGroup = new Group();
            this.entityPool = new EntityPool(transformManager, materialPool, maxPoolSize);
            this.defaultEntityScale = defaultEntityScale;
        }
        
        /**
         * Add or update an entity.
         */
        public void updateEntity(Object id, Point3f position, Color color, boolean selected, boolean hasContainer) {
            EntityState state = new EntityState(position, color, selected, hasContainer, defaultEntityScale);
            MeshView instance = entityPool.getInstance(id, state);
            
            // Ensure it's in the scene
            if (!entityGroup.getChildren().contains(instance)) {
                entityGroup.getChildren().add(instance);
            }
        }
        
        /**
         * Remove an entity.
         */
        public void removeEntity(Object id) {
            MeshView instance = entityPool.activeInstances.get(id);
            if (instance != null) {
                entityGroup.getChildren().remove(instance);
                entityPool.returnInstance(id);
            }
        }
        
        /**
         * Clear all entities.
         */
        public void clearAll() {
            entityGroup.getChildren().clear();
            entityPool.clear();
        }
        
        /**
         * Get the entity group for adding to scene.
         */
        public Group getEntityGroup() {
            return entityGroup;
        }
        
        /**
         * Get statistics.
         */
        public String getStatistics() {
            return entityPool.getStatistics();
        }
    }
}