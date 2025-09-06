package com.dyada.visualization.data;

/**
 * Marker interface for specific visualization data types.
 * All visualization data types should implement this interface
 * to provide access to base VisualizationData.
 */
public interface VisualizationDataType {
    
    /**
     * Converts this specialized visualization data back to base form.
     * 
     * @return base VisualizationData representation
     */
    VisualizationData asVisualizationData();
}