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
package com.hellblazer.luciferase.portal.collision;

import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedList;

/**
 * Real-time performance visualization for the collision system.
 * Displays timing charts, bottleneck analysis, and collision statistics.
 *
 * @author hal.hildebrand
 */
public class PerformanceVisualization extends BorderPane {
    
    private static final int MAX_DATA_POINTS = 100;
    private static final double UPDATE_INTERVAL_MS = 100;
    
    // Charts
    private LineChart<Number, Number> frameTimeChart;
    private BarChart<String, Number> operationChart;
    private PieChart collisionPairChart;
    
    // Data series
    private XYChart.Series<Number, Number> frameTimeSeries;
    private XYChart.Series<String, Number> operationSeries;
    private ObservableList<PieChart.Data> collisionPairData;
    
    // Tables
    private TableView<TimingEntry> timingTable;
    private TableView<CollisionPairEntry> collisionTable;
    private ObservableList<TimingEntry> timingData;
    private ObservableList<CollisionPairEntry> collisionData;
    
    // Controls
    private Label fpsLabel;
    private Label frameTimeLabel;
    private Label collisionCountLabel;
    private CheckBox enableProfiling;
    private Button resetStats;
    private Button exportReport;
    private TextArea reportArea;
    
    // Data tracking
    private final LinkedList<Double> frameTimeHistory = new LinkedList<>();
    private int frameCounter = 0;
    private Timeline updateTimeline;
    
    public PerformanceVisualization() {
        setupUI();
        startUpdateTimer();
    }
    
    /**
     * Initialize the user interface.
     */
    private void setupUI() {
        // Top controls
        setTop(createControlPanel());
        
        // Center tabbed pane with charts and tables
        var tabPane = new TabPane();
        tabPane.getTabs().addAll(
            createChartsTab(),
            createTimingTab(),
            createCollisionTab(),
            createReportTab()
        );
        setCenter(tabPane);
    }
    
    /**
     * Create the control panel.
     */
    private HBox createControlPanel() {
        var controlPanel = new HBox(10);
        controlPanel.setPadding(new Insets(10));
        controlPanel.setStyle("-fx-background-color: #f0f0f0;");
        
        // Performance indicators
        fpsLabel = new Label("FPS: --");
        fpsLabel.setStyle("-fx-font-weight: bold;");
        
        frameTimeLabel = new Label("Frame Time: -- ms");
        frameTimeLabel.setStyle("-fx-font-weight: bold;");
        
        collisionCountLabel = new Label("Collisions: --");
        collisionCountLabel.setStyle("-fx-font-weight: bold;");
        
        // Controls
        enableProfiling = new CheckBox("Enable Profiling");
        enableProfiling.setSelected(true);
        
        resetStats = new Button("Reset Stats");
        resetStats.setOnAction(e -> resetStatistics());
        
        exportReport = new Button("Export Report");
        exportReport.setOnAction(e -> exportPerformanceReport());
        
        controlPanel.getChildren().addAll(
            fpsLabel, new Separator(),
            frameTimeLabel, new Separator(),
            collisionCountLabel, new Separator(),
            enableProfiling, resetStats, exportReport
        );
        
        return controlPanel;
    }
    
    /**
     * Create the charts tab.
     */
    private Tab createChartsTab() {
        var tab = new Tab("Charts");
        tab.setClosable(false);
        
        var gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(10));
        
        // Frame time chart
        var xAxis1 = new NumberAxis();
        xAxis1.setLabel("Frame");
        var yAxis1 = new NumberAxis();
        yAxis1.setLabel("Time (ms)");
        frameTimeChart = new LineChart<>(xAxis1, yAxis1);
        frameTimeChart.setTitle("Frame Time History");
        frameTimeChart.setAnimated(false);
        frameTimeChart.setLegendVisible(false);
        
        frameTimeSeries = new XYChart.Series<>();
        frameTimeChart.getData().add(frameTimeSeries);
        
        // Operation performance chart
        var xAxis2 = new CategoryAxis();
        var yAxis2 = new NumberAxis();
        yAxis2.setLabel("Average Time (ms)");
        operationChart = new BarChart<>(xAxis2, yAxis2);
        operationChart.setTitle("Operation Performance");
        operationChart.setAnimated(false);
        operationChart.setLegendVisible(false);
        
        operationSeries = new XYChart.Series<>();
        operationChart.getData().add(operationSeries);
        
        // Collision pair distribution
        collisionPairChart = new PieChart();
        collisionPairChart.setTitle("Collision Pair Distribution");
        collisionPairChart.setAnimated(false);
        collisionPairData = FXCollections.observableArrayList();
        collisionPairChart.setData(collisionPairData);
        
        // Layout
        gridPane.add(frameTimeChart, 0, 0);
        gridPane.add(operationChart, 1, 0);
        gridPane.add(collisionPairChart, 0, 1, 2, 1);
        
        // Make charts resizable
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        gridPane.getColumnConstraints().addAll(col1, col2);
        
        RowConstraints row1 = new RowConstraints();
        row1.setPercentHeight(50);
        RowConstraints row2 = new RowConstraints();
        row2.setPercentHeight(50);
        gridPane.getRowConstraints().addAll(row1, row2);
        
        tab.setContent(gridPane);
        return tab;
    }
    
    /**
     * Create the timing details tab.
     */
    private Tab createTimingTab() {
        var tab = new Tab("Timing Details");
        tab.setClosable(false);
        
        timingTable = new TableView<>();
        timingData = FXCollections.observableArrayList();
        timingTable.setItems(timingData);
        
        // Columns
        var operationCol = new TableColumn<TimingEntry, String>("Operation");
        operationCol.setCellValueFactory(new PropertyValueFactory<>("operation"));
        operationCol.setPrefWidth(150);
        
        var countCol = new TableColumn<TimingEntry, Integer>("Count");
        countCol.setCellValueFactory(new PropertyValueFactory<>("count"));
        countCol.setPrefWidth(80);
        
        var totalCol = new TableColumn<TimingEntry, String>("Total (ms)");
        totalCol.setCellValueFactory(new PropertyValueFactory<>("totalTime"));
        totalCol.setPrefWidth(100);
        
        var avgCol = new TableColumn<TimingEntry, String>("Avg (ms)");
        avgCol.setCellValueFactory(new PropertyValueFactory<>("avgTime"));
        avgCol.setPrefWidth(100);
        
        var minCol = new TableColumn<TimingEntry, String>("Min (ms)");
        minCol.setCellValueFactory(new PropertyValueFactory<>("minTime"));
        minCol.setPrefWidth(100);
        
        var maxCol = new TableColumn<TimingEntry, String>("Max (ms)");
        maxCol.setCellValueFactory(new PropertyValueFactory<>("maxTime"));
        maxCol.setPrefWidth(100);
        
        timingTable.getColumns().addAll(operationCol, countCol, totalCol, avgCol, minCol, maxCol);
        
        tab.setContent(timingTable);
        return tab;
    }
    
    /**
     * Create the collision statistics tab.
     */
    private Tab createCollisionTab() {
        var tab = new Tab("Collision Stats");
        tab.setClosable(false);
        
        collisionTable = new TableView<>();
        collisionData = FXCollections.observableArrayList();
        collisionTable.setItems(collisionData);
        
        // Columns
        var pairCol = new TableColumn<CollisionPairEntry, String>("Shape Pair");
        pairCol.setCellValueFactory(new PropertyValueFactory<>("shapePair"));
        pairCol.setPrefWidth(200);
        
        var testsCol = new TableColumn<CollisionPairEntry, Integer>("Tests");
        testsCol.setCellValueFactory(new PropertyValueFactory<>("totalTests"));
        testsCol.setPrefWidth(100);
        
        var hitsCol = new TableColumn<CollisionPairEntry, Integer>("Hits");
        hitsCol.setCellValueFactory(new PropertyValueFactory<>("hits"));
        hitsCol.setPrefWidth(100);
        
        var hitRateCol = new TableColumn<CollisionPairEntry, String>("Hit Rate");
        hitRateCol.setCellValueFactory(new PropertyValueFactory<>("hitRate"));
        hitRateCol.setPrefWidth(100);
        
        collisionTable.getColumns().addAll(pairCol, testsCol, hitsCol, hitRateCol);
        
        tab.setContent(collisionTable);
        return tab;
    }
    
    /**
     * Create the performance report tab.
     */
    private Tab createReportTab() {
        var tab = new Tab("Report");
        tab.setClosable(false);
        
        reportArea = new TextArea();
        reportArea.setEditable(false);
        reportArea.setStyle("-fx-font-family: monospace;");
        
        tab.setContent(reportArea);
        return tab;
    }
    
    /**
     * Start the update timer for real-time data.
     */
    private void startUpdateTimer() {
        updateTimeline = new Timeline(new KeyFrame(
            Duration.millis(UPDATE_INTERVAL_MS),
            e -> updateVisualization()
        ));
        updateTimeline.setCycleCount(Timeline.INDEFINITE);
        updateTimeline.play();
    }
    
    /**
     * Update all visualizations with current profiling data.
     */
    private void updateVisualization() {
        if (!enableProfiling.isSelected()) {
            return;
        }
        
        var profiler = CollisionProfiler.getInstance();
        var summary = profiler.getPerformanceSummary();
        
        // Update labels
        fpsLabel.setText(String.format("FPS: %.1f", summary.fps()));
        frameTimeLabel.setText(String.format("Frame Time: %.2f ms", summary.avgFrameTimeNanos() / 1_000_000.0));
        
        // Update frame time chart
        updateFrameTimeChart(summary.avgFrameTimeNanos() / 1_000_000.0);
        
        // Update operation chart
        updateOperationChart(profiler.getAllTimingStats());
        
        // Update collision pair chart
        updateCollisionPairChart(profiler.getCollisionPairStats());
        
        // Update timing table
        updateTimingTable(profiler.getAllTimingStats());
        
        // Update collision table
        updateCollisionTable(profiler.getCollisionPairStats());
        
        // Update report
        updateReport(profiler.generateReport());
    }
    
    /**
     * Update frame time chart.
     */
    private void updateFrameTimeChart(double frameTime) {
        frameTimeHistory.add(frameTime);
        if (frameTimeHistory.size() > MAX_DATA_POINTS) {
            frameTimeHistory.removeFirst();
        }
        
        frameTimeSeries.getData().clear();
        for (int i = 0; i < frameTimeHistory.size(); i++) {
            frameTimeSeries.getData().add(new XYChart.Data<>(i, frameTimeHistory.get(i)));
        }
    }
    
    /**
     * Update operation performance chart.
     */
    private void updateOperationChart(java.util.Map<String, CollisionProfiler.TimingStats> stats) {
        operationSeries.getData().clear();
        
        stats.entrySet().stream()
            .filter(e -> !e.getKey().equals("frame"))
            .sorted((a, b) -> Double.compare(b.getValue().getAverageNanos(), a.getValue().getAverageNanos()))
            .limit(10)
            .forEach(e -> {
                var avgMs = e.getValue().getAverageNanos() / 1_000_000.0;
                operationSeries.getData().add(new XYChart.Data<>(e.getKey(), avgMs));
            });
    }
    
    /**
     * Update collision pair distribution chart.
     */
    private void updateCollisionPairChart(CollisionProfiler.CollisionPairStats pairStats) {
        collisionPairData.clear();
        
        pairStats.pairs().stream()
            .sorted((a, b) -> Long.compare(b.totalTests(), a.totalTests()))
            .limit(8)
            .forEach(pair -> {
                collisionPairData.add(new PieChart.Data(pair.pairKey(), pair.totalTests()));
            });
    }
    
    /**
     * Update timing details table.
     */
    private void updateTimingTable(java.util.Map<String, CollisionProfiler.TimingStats> stats) {
        timingData.clear();
        
        stats.forEach((operation, timingStats) -> {
            timingData.add(new TimingEntry(
                operation,
                (int) timingStats.getSampleCount(),
                String.format("%.3f", timingStats.getTotalNanos() / 1_000_000.0),
                String.format("%.3f", timingStats.getAverageNanos() / 1_000_000.0),
                String.format("%.3f", timingStats.getMinNanos() / 1_000_000.0),
                String.format("%.3f", timingStats.getMaxNanos() / 1_000_000.0)
            ));
        });
    }
    
    /**
     * Update collision statistics table.
     */
    private void updateCollisionTable(CollisionProfiler.CollisionPairStats pairStats) {
        collisionData.clear();
        
        pairStats.pairs().forEach(pair -> {
            collisionData.add(new CollisionPairEntry(
                pair.pairKey(),
                (int) pair.totalTests(),
                (int) pair.hits(),
                String.format("%.2f%%", pair.hitRate() * 100)
            ));
        });
    }
    
    /**
     * Update performance report.
     */
    private void updateReport(String report) {
        reportArea.setText(report);
    }
    
    /**
     * Reset all statistics.
     */
    private void resetStatistics() {
        CollisionProfiler.getInstance().reset();
        frameTimeHistory.clear();
        frameTimeSeries.getData().clear();
        operationSeries.getData().clear();
        collisionPairData.clear();
        timingData.clear();
        collisionData.clear();
        reportArea.clear();
        
        fpsLabel.setText("FPS: --");
        frameTimeLabel.setText("Frame Time: -- ms");
        collisionCountLabel.setText("Collisions: --");
    }
    
    /**
     * Export performance report to file.
     */
    private void exportPerformanceReport() {
        var report = CollisionProfiler.getInstance().generateReport();
        
        // In a real application, you'd use FileChooser to save to file
        // For now, just copy to clipboard or print to console
        System.out.println("=== EXPORTED PERFORMANCE REPORT ===");
        System.out.println(report);
        System.out.println("=== END REPORT ===");
        
        // Show confirmation
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Export Complete");
        alert.setHeaderText("Performance report exported");
        alert.setContentText("Report has been printed to console. In a full implementation, this would save to a file.");
        alert.showAndWait();
    }
    
    /**
     * Stop the visualization updates.
     */
    public void stop() {
        if (updateTimeline != null) {
            updateTimeline.stop();
        }
    }
    
    // Data classes for table views
    public static class TimingEntry {
        private final String operation;
        private final int count;
        private final String totalTime;
        private final String avgTime;
        private final String minTime;
        private final String maxTime;
        
        public TimingEntry(String operation, int count, String totalTime, String avgTime, String minTime, String maxTime) {
            this.operation = operation;
            this.count = count;
            this.totalTime = totalTime;
            this.avgTime = avgTime;
            this.minTime = minTime;
            this.maxTime = maxTime;
        }
        
        public String getOperation() { return operation; }
        public int getCount() { return count; }
        public String getTotalTime() { return totalTime; }
        public String getAvgTime() { return avgTime; }
        public String getMinTime() { return minTime; }
        public String getMaxTime() { return maxTime; }
    }
    
    public static class CollisionPairEntry {
        private final String shapePair;
        private final int totalTests;
        private final int hits;
        private final String hitRate;
        
        public CollisionPairEntry(String shapePair, int totalTests, int hits, String hitRate) {
            this.shapePair = shapePair;
            this.totalTests = totalTests;
            this.hits = hits;
            this.hitRate = hitRate;
        }
        
        public String getShapePair() { return shapePair; }
        public int getTotalTests() { return totalTests; }
        public int getHits() { return hits; }
        public String getHitRate() { return hitRate; }
    }
}