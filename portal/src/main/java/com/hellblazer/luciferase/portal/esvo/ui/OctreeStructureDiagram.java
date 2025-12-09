/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.esvo.ui;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.util.ESVONodeGeometry;
import com.hellblazer.luciferase.esvo.util.ESVOTopology;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 2D side panel showing octree tree structure with parent-child relationships,
 * node properties, and highlighting of active nodes during ray traversal.
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>Tree layout showing hierarchical structure</li>
 *   <li>Interactive node selection with properties display</li>
 *   <li>Color-coded nodes by depth level</li>
 *   <li>Highlighting of active nodes during ray casting</li>
 *   <li>Zoom and pan controls for large trees</li>
 *   <li>Collapsible subtrees for better navigation</li>
 * </ul>
 * 
 * @author hal.hildebrand
 */
public class OctreeStructureDiagram extends BorderPane {
    
    private static final Logger log = LoggerFactory.getLogger(OctreeStructureDiagram.class);
    
    // Layout constants
    private static final double NODE_RADIUS = 20.0;
    private static final double NODE_SPACING_X = 60.0;
    private static final double NODE_SPACING_Y = 80.0;
    private static final double CANVAS_PADDING = 50.0;
    
    // Colors for different levels (depth gradient)
    private static final Color[] LEVEL_COLORS = {
        Color.rgb(255, 100, 100),  // Level 0 - Red
        Color.rgb(255, 150, 100),  // Level 1 - Orange
        Color.rgb(255, 200, 100),  // Level 2 - Yellow-Orange
        Color.rgb(200, 255, 100),  // Level 3 - Yellow-Green
        Color.rgb(100, 255, 100),  // Level 4 - Green
        Color.rgb(100, 255, 200),  // Level 5 - Cyan-Green
        Color.rgb(100, 200, 255),  // Level 6 - Light Blue
        Color.rgb(100, 100, 255),  // Level 7 - Blue
        Color.rgb(150, 100, 255),  // Level 8 - Purple
        Color.rgb(200, 100, 255),  // Level 9 - Violet
    };
    
    // UI Components
    private final Canvas canvas;
    private final ScrollPane scrollPane;
    private final TextArea propertiesArea;
    private final Label statusLabel;
    private final Slider zoomSlider;
    
    // Octree data
    private ESVOOctreeData octreeData;
    private int maxDepth;
    
    // Layout state
    private final Map<Integer, NodePosition> nodePositions = new HashMap<>();
    private final Set<Integer> collapsedNodes = new HashSet<>();
    private final Set<Integer> highlightedNodes = new HashSet<>();
    private Integer selectedNode = null;
    
    // View state
    private double zoomLevel = 1.0;
    private double offsetX = 0.0;
    private double offsetY = 0.0;
    
    // Drag state
    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    
    /**
     * Node position in the tree layout.
     */
    private static class NodePosition {
        final int index;
        final int level;
        final double x;
        final double y;
        
        NodePosition(int index, int level, double x, double y) {
            this.index = index;
            this.level = level;
            this.x = x;
            this.y = y;
        }
    }
    
    public OctreeStructureDiagram() {
        // Create canvas for tree visualization
        canvas = new Canvas(800, 600);
        canvas.setOnMouseClicked(this::handleMouseClick);
        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseDragged(this::handleMouseDragged);
        
        scrollPane = new ScrollPane(canvas);
        scrollPane.setPannable(false);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        
        // Create properties panel
        VBox propertiesPanel = new VBox(10);
        propertiesPanel.setPadding(new Insets(10));
        propertiesPanel.setStyle("-fx-background-color: #f9f9f9; -fx-border-color: #cccccc;");
        
        Label propertiesLabel = new Label("Node Properties");
        propertiesLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        
        propertiesArea = new TextArea();
        propertiesArea.setEditable(false);
        propertiesArea.setPrefRowCount(8);
        propertiesArea.setWrapText(true);
        propertiesArea.setStyle("-fx-font-family: monospace; -fx-font-size: 10;");
        propertiesArea.setText("No node selected.\nClick on a node to view its properties.");
        
        // Zoom controls
        Label zoomLabel = new Label("Zoom:");
        zoomLabel.setFont(Font.font("System", FontWeight.BOLD, 11));
        
        zoomSlider = new Slider(0.5, 3.0, 1.0);
        zoomSlider.setShowTickLabels(true);
        zoomSlider.setShowTickMarks(true);
        zoomSlider.setMajorTickUnit(0.5);
        zoomSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            zoomLevel = newVal.doubleValue();
            redraw();
        });
        
        Button resetViewButton = new Button("Reset View");
        resetViewButton.setMaxWidth(Double.MAX_VALUE);
        resetViewButton.setOnAction(e -> resetView());
        
        Button collapseAllButton = new Button("Collapse All");
        collapseAllButton.setMaxWidth(Double.MAX_VALUE);
        collapseAllButton.setOnAction(e -> collapseAll());
        
        Button expandAllButton = new Button("Expand All");
        expandAllButton.setMaxWidth(Double.MAX_VALUE);
        expandAllButton.setOnAction(e -> expandAll());
        
        propertiesPanel.getChildren().addAll(
            propertiesLabel,
            propertiesArea,
            new Separator(),
            zoomLabel,
            zoomSlider,
            resetViewButton,
            collapseAllButton,
            expandAllButton
        );
        
        // Status bar
        statusLabel = new Label("No octree loaded");
        statusLabel.setPadding(new Insets(5));
        statusLabel.setStyle("-fx-background-color: #e0e0e0; -fx-border-color: #cccccc;");
        
        // Layout
        setCenter(scrollPane);
        setRight(propertiesPanel);
        setBottom(statusLabel);
        
        // Initial draw
        redraw();
    }
    
    /**
     * Update the diagram with new octree data.
     */
    public void setOctreeData(ESVOOctreeData data, int maxDepth) {
        this.octreeData = data;
        this.maxDepth = maxDepth;
        
        // Reset state
        nodePositions.clear();
        collapsedNodes.clear();
        highlightedNodes.clear();
        selectedNode = null;
        
        // Calculate layout
        if (data != null && data.getNodeCount() > 0) {
            calculateLayout();
            statusLabel.setText(String.format("Octree: %d nodes, depth %d", 
                                             data.getNodeCount(), maxDepth));
        } else {
            statusLabel.setText("No octree loaded");
        }
        
        // Reset view
        resetView();
        
        // Redraw
        redraw();
    }
    
    /**
     * Highlight nodes that were traversed during ray casting.
     */
    public void highlightNodes(Collection<Integer> nodeIndices) {
        highlightedNodes.clear();
        if (nodeIndices != null) {
            highlightedNodes.addAll(nodeIndices);
        }
        redraw();
    }
    
    /**
     * Clear all highlighted nodes.
     */
    public void clearHighlights() {
        highlightedNodes.clear();
        redraw();
    }
    
    /**
     * Calculate tree layout using a simple level-based approach.
     * Each level is arranged horizontally with even spacing.
     */
    private void calculateLayout() {
        if (octreeData == null || octreeData.getNodeCount() == 0) {
            return;
        }
        
        // Count nodes at each level to determine spacing
        Map<Integer, Integer> levelCounts = new HashMap<>();
        Map<Integer, Integer> levelOffsets = new HashMap<>();
        
        for (int i = 0; i < octreeData.getNodeCount(); i++) {
            int level = ESVOTopology.getNodeLevel(i);
            levelCounts.put(level, levelCounts.getOrDefault(level, 0) + 1);
            levelOffsets.putIfAbsent(level, 0);
        }
        
        // Position nodes level by level
        for (int i = 0; i < octreeData.getNodeCount(); i++) {
            int level = ESVOTopology.getNodeLevel(i);
            int nodesAtLevel = levelCounts.get(level);
            int offset = levelOffsets.get(level);
            
            // Calculate position
            double y = CANVAS_PADDING + level * NODE_SPACING_Y;
            double totalWidth = (nodesAtLevel - 1) * NODE_SPACING_X;
            double startX = CANVAS_PADDING + (canvas.getWidth() - 2 * CANVAS_PADDING - totalWidth) / 2.0;
            double x = startX + offset * NODE_SPACING_X;
            
            nodePositions.put(i, new NodePosition(i, level, x, y));
            levelOffsets.put(level, offset + 1);
        }
        
        // Adjust canvas size if needed
        double maxY = nodePositions.values().stream()
                                    .mapToDouble(p -> p.y)
                                    .max()
                                    .orElse(0.0);
        
        if (maxY + CANVAS_PADDING > canvas.getHeight()) {
            canvas.setHeight(maxY + 2 * CANVAS_PADDING);
        }
    }
    
    /**
     * Redraw the entire diagram.
     */
    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        
        // Clear canvas
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        
        if (octreeData == null || nodePositions.isEmpty()) {
            // Draw "no data" message
            gc.setFill(Color.GRAY);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.setFont(Font.font("System", FontWeight.NORMAL, 14));
            gc.fillText("No octree data loaded", 
                       canvas.getWidth() / 2, 
                       canvas.getHeight() / 2);
            return;
        }
        
        // Apply transformations
        gc.save();
        gc.translate(offsetX, offsetY);
        gc.scale(zoomLevel, zoomLevel);
        
        // Draw edges first (so they appear behind nodes)
        drawEdges(gc);
        
        // Draw nodes
        drawNodes(gc);
        
        gc.restore();
    }
    
    /**
     * Draw edges between parent and child nodes.
     */
    private void drawEdges(GraphicsContext gc) {
        gc.setLineWidth(1.5);
        gc.setStroke(Color.GRAY);
        
        for (NodePosition childPos : nodePositions.values()) {
            if (childPos.index == ESVOTopology.ROOT_INDEX) {
                continue; // Root has no parent
            }
            
            // Skip if parent is collapsed
            int parentIndex = ESVOTopology.getParentIndex(childPos.index);
            if (collapsedNodes.contains(parentIndex)) {
                continue;
            }
            
            NodePosition parentPos = nodePositions.get(parentIndex);
            if (parentPos != null) {
                // Draw line from parent to child
                gc.strokeLine(
                    parentPos.x, parentPos.y + NODE_RADIUS,
                    childPos.x, childPos.y - NODE_RADIUS
                );
            }
        }
    }
    
    /**
     * Draw octree nodes.
     */
    private void drawNodes(GraphicsContext gc) {
        // Draw nodes in order (so higher levels appear on top)
        for (NodePosition pos : nodePositions.values()) {
            // Skip if parent is collapsed
            if (pos.index != ESVOTopology.ROOT_INDEX) {
                int parentIndex = ESVOTopology.getParentIndex(pos.index);
                if (collapsedNodes.contains(parentIndex)) {
                    continue;
                }
            }
            
            drawNode(gc, pos);
        }
    }
    
    /**
     * Draw a single node.
     */
    private void drawNode(GraphicsContext gc, NodePosition pos) {
        // Determine node color
        Color nodeColor = LEVEL_COLORS[pos.level % LEVEL_COLORS.length];
        
        // Highlighted nodes
        if (highlightedNodes.contains(pos.index)) {
            nodeColor = Color.YELLOW;
        }
        
        // Selected node
        boolean isSelected = selectedNode != null && pos.index == selectedNode;
        
        // Draw node circle
        gc.setFill(nodeColor);
        if (isSelected) {
            gc.setStroke(Color.BLACK);
            gc.setLineWidth(3.0);
        } else {
            gc.setStroke(Color.DARKGRAY);
            gc.setLineWidth(1.5);
        }
        
        gc.fillOval(pos.x - NODE_RADIUS, pos.y - NODE_RADIUS, 
                   NODE_RADIUS * 2, NODE_RADIUS * 2);
        gc.strokeOval(pos.x - NODE_RADIUS, pos.y - NODE_RADIUS, 
                     NODE_RADIUS * 2, NODE_RADIUS * 2);
        
        // Draw node index
        gc.setFill(Color.BLACK);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(Font.font("System", FontWeight.BOLD, 10));
        gc.fillText(String.valueOf(pos.index), pos.x, pos.y);
        
        // Draw collapse indicator if node has children
        if (hasChildren(pos.index)) {
            double indicatorSize = 8.0;
            double indicatorX = pos.x + NODE_RADIUS - indicatorSize / 2;
            double indicatorY = pos.y - NODE_RADIUS + indicatorSize / 2;
            
            gc.setFill(collapsedNodes.contains(pos.index) ? Color.RED : Color.GREEN);
            gc.fillOval(indicatorX, indicatorY, indicatorSize, indicatorSize);
        }
    }
    
    /**
     * Check if a node has children in the current octree.
     */
    private boolean hasChildren(int nodeIndex) {
        int[] childIndices = ESVOTopology.getChildIndices(nodeIndex);
        for (int childIndex : childIndices) {
            if (childIndex < octreeData.getNodeCount()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Handle mouse click on canvas.
     */
    private void handleMouseClick(MouseEvent event) {
        // Transform mouse coordinates
        double mouseX = (event.getX() - offsetX) / zoomLevel;
        double mouseY = (event.getY() - offsetY) / zoomLevel;
        
        // Find clicked node
        Integer clickedNode = findNodeAt(mouseX, mouseY);
        
        if (clickedNode != null) {
            if (event.getClickCount() == 2) {
                // Double-click: toggle collapse
                toggleCollapse(clickedNode);
            } else {
                // Single-click: select node
                selectNode(clickedNode);
            }
        }
    }
    
    /**
     * Find node at given coordinates.
     */
    private Integer findNodeAt(double x, double y) {
        for (NodePosition pos : nodePositions.values()) {
            double dx = x - pos.x;
            double dy = y - pos.y;
            double distance = Math.sqrt(dx * dx + dy * dy);
            
            if (distance <= NODE_RADIUS) {
                return pos.index;
            }
        }
        return null;
    }
    
    /**
     * Select a node and display its properties.
     */
    private void selectNode(int nodeIndex) {
        selectedNode = nodeIndex;
        displayNodeProperties(nodeIndex);
        redraw();
    }
    
    /**
     * Display properties of a node in the properties area.
     */
    private void displayNodeProperties(int nodeIndex) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("NODE PROPERTIES\n");
        sb.append("===============\n\n");
        
        sb.append(String.format("Index: %d\n", nodeIndex));
        sb.append(String.format("Level: %d\n", ESVOTopology.getNodeLevel(nodeIndex)));
        
        if (nodeIndex != ESVOTopology.ROOT_INDEX) {
            sb.append(String.format("Parent: %d\n", ESVOTopology.getParentIndex(nodeIndex)));
            sb.append(String.format("Octant: %d\n", ESVOTopology.getOctantIndex(nodeIndex)));
        } else {
            sb.append("Parent: None (root)\n");
            sb.append("Octant: None (root)\n");
        }
        
        // Children
        int[] childIndices = ESVOTopology.getChildIndices(nodeIndex);
        sb.append("\nChildren:\n");
        boolean hasAnyChildren = false;
        for (int i = 0; i < childIndices.length; i++) {
            if (childIndices[i] < octreeData.getNodeCount()) {
                sb.append(String.format("  [%d]: %d\n", i, childIndices[i]));
                hasAnyChildren = true;
            }
        }
        if (!hasAnyChildren) {
            sb.append("  None (leaf node)\n");
        }
        
        // Bounds
        var bounds = ESVONodeGeometry.getNodeBounds(nodeIndex, maxDepth);
        sb.append(String.format("\nBounds:\n"));
        sb.append(String.format("  Min: (%.4f, %.4f, %.4f)\n", 
                               bounds.min.x, bounds.min.y, bounds.min.z));
        sb.append(String.format("  Max: (%.4f, %.4f, %.4f)\n", 
                               bounds.max.x, bounds.max.y, bounds.max.z));
        
        var center = bounds.getCenter();
        sb.append(String.format("  Center: (%.4f, %.4f, %.4f)\n", 
                               center.x, center.y, center.z));
        
        var size = bounds.getSize();
        sb.append(String.format("  Size: %.4f\n", size.x));
        
        propertiesArea.setText(sb.toString());
    }
    
    /**
     * Toggle collapse state of a node.
     */
    private void toggleCollapse(int nodeIndex) {
        if (collapsedNodes.contains(nodeIndex)) {
            collapsedNodes.remove(nodeIndex);
        } else {
            collapsedNodes.add(nodeIndex);
        }
        redraw();
    }
    
    /**
     * Collapse all nodes.
     */
    private void collapseAll() {
        collapsedNodes.clear();
        for (int i = 0; i < octreeData.getNodeCount(); i++) {
            if (hasChildren(i)) {
                collapsedNodes.add(i);
            }
        }
        redraw();
    }
    
    /**
     * Expand all nodes.
     */
    private void expandAll() {
        collapsedNodes.clear();
        redraw();
    }
    
    /**
     * Reset view to default zoom and offset.
     */
    private void resetView() {
        zoomLevel = 1.0;
        zoomSlider.setValue(1.0);
        offsetX = 0.0;
        offsetY = 0.0;
        redraw();
    }
    
    /**
     * Handle mouse pressed for panning.
     */
    private void handleMousePressed(MouseEvent event) {
        lastMouseX = event.getX();
        lastMouseY = event.getY();
    }
    
    /**
     * Handle mouse dragged for panning.
     */
    private void handleMouseDragged(MouseEvent event) {
        double dx = event.getX() - lastMouseX;
        double dy = event.getY() - lastMouseY;
        
        offsetX += dx;
        offsetY += dy;
        
        lastMouseX = event.getX();
        lastMouseY = event.getY();
        
        redraw();
    }
}
