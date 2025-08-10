import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import com.hellblazer.luciferase.render.voxel.pipeline.TriangleBoxIntersection;

public class TestDebug {
    public static void main(String[] args) {
        // testFullCoverage scenario
        var v0 = new Point3f(-5.0f, -5.0f, 0.0f);
        var v1 = new Point3f(5.0f, -5.0f, 0.0f);
        var v2 = new Point3f(0.0f, 5.0f, 0.0f);
        
        var boxCenter = new Point3f(0, 0, 0);
        var boxHalfSize = new Vector3f(0.1f, 0.1f, 0.1f);
        
        System.out.println("Testing large triangle covering small box:");
        System.out.println("Triangle: v0=" + v0 + ", v1=" + v1 + ", v2=" + v2);
        System.out.println("Box: center=" + boxCenter + ", halfSize=" + boxHalfSize);
        
        boolean intersects = TriangleBoxIntersection.intersects(v0, v1, v2, boxCenter, boxHalfSize);
        System.out.println("Intersects: " + intersects);
        
        var clippedVertices = new java.util.ArrayList<TriangleBoxIntersection.BarycentricCoord>();
        int numClipped = TriangleBoxIntersection.clipTriangleToBox(
            clippedVertices, v0, v1, v2, boxCenter, boxHalfSize
        );
        
        System.out.println("Number of clipped vertices: " + numClipped);
        for (int i = 0; i < clippedVertices.size(); i++) {
            var v = clippedVertices.get(i);
            System.out.println("  Vertex " + i + ": u=" + v.u + ", v=" + v.v + ", w=" + v.w + 
                              " (sum=" + (v.u + v.v + v.w) + ")");
        }
        
        float coverage = TriangleBoxIntersection.computeCoverage(
            v0, v1, v2, boxCenter, boxHalfSize
        );
        
        System.out.println("Coverage: " + coverage);
        System.out.println("Expected: > 0.8");
        System.out.println("Test passes: " + (coverage > 0.8f));
    }
}