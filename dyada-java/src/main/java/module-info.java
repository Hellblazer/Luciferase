module com.dyada {
    requires java.base;
    requires java.desktop;
    requires java.management;
    requires org.slf4j;
    
    exports com.dyada.core;
    exports com.dyada.core.coordinates;
    exports com.dyada.core.descriptors;
    exports com.dyada.core.linearization;
    exports com.dyada.core.bitarray;
    exports com.dyada.discretization;
    exports com.dyada.refinement;
    exports com.dyada.transformations;
    exports com.dyada.visualization;
    exports com.dyada.visualization.data;
    exports com.dyada.performance;
    exports com.dyada.exceptions;
}