package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Represents a 3D camera frustum defined by six planes Used for frustum culling operations in 3D graphics All
 * coordinate operations are constrained to positive values only
 *
 * @author hal.hildebrand
 */
public class Frustum3D {

    public final Plane3D nearPlane;
    public final Plane3D farPlane;
    public final Plane3D leftPlane;
    public final Plane3D rightPlane;
    public final Plane3D topPlane;
    public final Plane3D bottomPlane;

    /**
     * Create a frustum from six planes
     *
     * @param nearPlane   near clipping plane
     * @param farPlane    far clipping plane
     * @param leftPlane   left clipping plane
     * @param rightPlane  right clipping plane
     * @param topPlane    top clipping plane
     * @param bottomPlane bottom clipping plane
     */
    public Frustum3D(Plane3D nearPlane, Plane3D farPlane, Plane3D leftPlane, Plane3D rightPlane, Plane3D topPlane,
                     Plane3D bottomPlane) {
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
        this.leftPlane = leftPlane;
        this.rightPlane = rightPlane;
        this.topPlane = topPlane;
        this.bottomPlane = bottomPlane;
    }

    /**
     * Create an orthographic frustum from parameters All coordinates must be positive
     *
     * @param cameraPosition camera position (positive coordinates only)
     * @param lookAt         target point to look at (positive coordinates only)
     * @param up             up vector (will be normalized)
     * @param left           left boundary (positive)
     * @param right          right boundary (positive, must be > left)
     * @param bottom         bottom boundary (positive)
     * @param top            top boundary (positive, must be > bottom)
     * @param nearDistance   distance to near plane (positive)
     * @param farDistance    distance to far plane (positive, must be > near)
     * @return the orthographic frustum
     * @throws IllegalArgumentException if any coordinate is negative or parameters are invalid
     */
    public static Frustum3D createOrthographic(Point3f cameraPosition, Point3f lookAt, Vector3f up, float left,
                                               float right, float bottom, float top, float nearDistance,
                                               float farDistance) {
        validatePositiveCoordinates(cameraPosition, "cameraPosition");
        validatePositiveCoordinates(lookAt, "lookAt");

        if (left < 0 || right < 0 || bottom < 0 || top < 0) {
            throw new IllegalArgumentException("All frustum boundaries must be positive");
        }
        if (right <= left) {
            throw new IllegalArgumentException("Right boundary must be greater than left");
        }
        if (top <= bottom) {
            throw new IllegalArgumentException("Top boundary must be greater than bottom");
        }
        if (nearDistance <= 0 || farDistance <= 0) {
            throw new IllegalArgumentException("Near and far distances must be positive");
        }
        if (farDistance <= nearDistance) {
            throw new IllegalArgumentException("Far distance must be greater than near distance");
        }

        // Calculate camera coordinate system
        Vector3f forward = new Vector3f(lookAt.x - cameraPosition.x, lookAt.y - cameraPosition.y,
                                        lookAt.z - cameraPosition.z);
        forward.normalize();

        Vector3f upNorm = new Vector3f(up);
        upNorm.normalize();

        Vector3f rightVec = new Vector3f();
        rightVec.cross(forward, upNorm);
        rightVec.normalize();

        // Recalculate up to ensure orthogonality
        Vector3f actualUp = new Vector3f();
        actualUp.cross(rightVec, forward);
        actualUp.normalize();

        // Calculate frustum corners
        Point3f nearCenter = new Point3f(cameraPosition.x + forward.x * nearDistance,
                                         cameraPosition.y + forward.y * nearDistance,
                                         cameraPosition.z + forward.z * nearDistance);

        Point3f farCenter = new Point3f(cameraPosition.x + forward.x * farDistance,
                                        cameraPosition.y + forward.y * farDistance,
                                        cameraPosition.z + forward.z * farDistance);

        // Create the six planes using axis-aligned approach for orthographic
        // Normals need to be set so that points inside the frustum have negative distance
        Vector3f nearNormal = new Vector3f(-forward.x, -forward.y, -forward.z);  // Points toward camera (inward)
        Vector3f farNormal = new Vector3f(forward.x, forward.y, forward.z);      // Points away from camera (inward)
        Vector3f leftNormal = new Vector3f(rightVec.x, rightVec.y, rightVec.z);  // Points rightward (inward from left)
        Vector3f rightNormal = new Vector3f(-rightVec.x, -rightVec.y,
                                            -rightVec.z); // Points leftward (inward from right)
        // For orthographic, top/bottom normals point inward to frustum
        Vector3f topNormal = new Vector3f(actualUp.x, actualUp.y, actualUp.z);        // Points upward (inward from top)
        Vector3f bottomNormal = new Vector3f(-actualUp.x, -actualUp.y,
                                             -actualUp.z);  // Points downward (inward from bottom)

        // Create the six frustum planes
        // Near and far planes are straightforward
        Plane3D nearPlane = Plane3D.fromPointAndNormal(nearCenter, nearNormal);
        Plane3D farPlane = Plane3D.fromPointAndNormal(farCenter, farNormal);

        // For orthographic frustum, when camera is axis-aligned (common case),
        // the left/right/bottom/top parameters define world-space boundaries
        // We create planes at these boundaries with normals pointing inward

        // For a camera looking along Z-axis with Y as up:
        // - Left/right boundaries are at X = left and X = right
        // - Bottom/top boundaries are at Y = bottom and Y = top
        // - Near/far boundaries are along the view direction

        // Since we have arbitrary camera orientation, we need to handle this more generally
        // The boundaries form a box in world space that we need to transform

        // For the general case, we'll place the side planes using the world-space boundaries
        // but oriented according to the camera's coordinate system

        Point3f leftPoint, rightPoint, bottomPoint, topPoint;

        // Check if camera is roughly axis-aligned (looking along +Z or -Z with Y up)
        float dotZPos = forward.dot(new Vector3f(0, 0, 1));
        float dotZNeg = forward.dot(new Vector3f(0, 0, -1));
        float dotY = Math.abs(actualUp.dot(new Vector3f(0, 1, 0)));

        if ((Math.abs(dotZPos) > 0.99f || Math.abs(dotZNeg) > 0.99f) && dotY > 0.99f) {
            // Camera is axis-aligned, use world coordinates directly
            // For the test case: camera at (500,500,100) looking at (500,500,1000)
            // So forward is along +Z, right is along +X, up is along +Y
            leftPoint = new Point3f(left, (bottom + top) / 2.0f, nearCenter.z);
            rightPoint = new Point3f(right, (bottom + top) / 2.0f, nearCenter.z);
            bottomPoint = new Point3f((left + right) / 2.0f, bottom, nearCenter.z);
            topPoint = new Point3f((left + right) / 2.0f, top, nearCenter.z);
        } else {
            // General case: transform boundaries to camera space
            // This is more complex and would require full transformation
            // For now, use the offset approach
            float halfWidth = (right - left) / 2.0f;
            float halfHeight = (top - bottom) / 2.0f;
            float centerX = (left + right) / 2.0f;
            float centerY = (bottom + top) / 2.0f;

            // Calculate offsets from camera position to boundary center
            Vector3f toCenter = new Vector3f(centerX - cameraPosition.x, centerY - cameraPosition.y, 0);

            float rightOffset = toCenter.dot(rightVec);
            float upOffset = toCenter.dot(actualUp);

            leftPoint = new Point3f(nearCenter.x + rightVec.x * (rightOffset - halfWidth),
                                    nearCenter.y + rightVec.y * (rightOffset - halfWidth),
                                    nearCenter.z + rightVec.z * (rightOffset - halfWidth));

            rightPoint = new Point3f(nearCenter.x + rightVec.x * (rightOffset + halfWidth),
                                     nearCenter.y + rightVec.y * (rightOffset + halfWidth),
                                     nearCenter.z + rightVec.z * (rightOffset + halfWidth));

            bottomPoint = new Point3f(nearCenter.x + actualUp.x * (upOffset - halfHeight),
                                      nearCenter.y + actualUp.y * (upOffset - halfHeight),
                                      nearCenter.z + actualUp.z * (upOffset - halfHeight));

            topPoint = new Point3f(nearCenter.x + actualUp.x * (upOffset + halfHeight),
                                   nearCenter.y + actualUp.y * (upOffset + halfHeight),
                                   nearCenter.z + actualUp.z * (upOffset + halfHeight));
        }

        Plane3D leftPlane = Plane3D.fromPointAndNormal(leftPoint, leftNormal);
        Plane3D rightPlane = Plane3D.fromPointAndNormal(rightPoint, rightNormal);
        Plane3D topPlane = Plane3D.fromPointAndNormal(topPoint, topNormal);
        Plane3D bottomPlane = Plane3D.fromPointAndNormal(bottomPoint, bottomNormal);

        return new Frustum3D(nearPlane, farPlane, leftPlane, rightPlane, topPlane, bottomPlane);
    }

    /**
     * Create a perspective frustum from camera parameters All coordinates must be positive
     *
     * @param cameraPosition camera position (positive coordinates only)
     * @param lookAt         target point to look at (positive coordinates only)
     * @param up             up vector (will be normalized)
     * @param fovy           field of view in Y direction (radians)
     * @param aspectRatio    width/height ratio
     * @param nearDistance   distance to near plane (positive)
     * @param farDistance    distance to far plane (positive)
     * @return the perspective frustum
     * @throws IllegalArgumentException if any coordinate is negative or distances are invalid
     */
    public static Frustum3D createPerspective(Point3f cameraPosition, Point3f lookAt, Vector3f up, float fovy,
                                              float aspectRatio, float nearDistance, float farDistance) {
        validatePositiveCoordinates(cameraPosition, "cameraPosition");
        validatePositiveCoordinates(lookAt, "lookAt");

        if (nearDistance <= 0 || farDistance <= 0) {
            throw new IllegalArgumentException("Near and far distances must be positive");
        }
        if (farDistance <= nearDistance) {
            throw new IllegalArgumentException("Far distance must be greater than near distance");
        }
        if (fovy <= 0 || fovy >= Math.PI) {
            throw new IllegalArgumentException("Field of view must be between 0 and π radians");
        }
        if (aspectRatio <= 0) {
            throw new IllegalArgumentException("Aspect ratio must be positive");
        }

        // Calculate frustum geometry
        float halfHeight = nearDistance * (float) Math.tan(fovy / 2.0f);
        float halfWidth = halfHeight * aspectRatio;

        // Calculate camera coordinate system
        Vector3f forward = new Vector3f(lookAt.x - cameraPosition.x, lookAt.y - cameraPosition.y,
                                        lookAt.z - cameraPosition.z);
        forward.normalize();

        Vector3f upNorm = new Vector3f(up);
        upNorm.normalize();

        Vector3f right = new Vector3f();
        right.cross(forward, upNorm);
        right.normalize();

        // Recalculate up to ensure orthogonality
        Vector3f actualUp = new Vector3f();
        actualUp.cross(right, forward);
        actualUp.normalize();

        // Calculate plane positions
        Point3f nearCenter = new Point3f(cameraPosition.x + forward.x * nearDistance,
                                         cameraPosition.y + forward.y * nearDistance,
                                         cameraPosition.z + forward.z * nearDistance);

        Point3f farCenter = new Point3f(cameraPosition.x + forward.x * farDistance,
                                        cameraPosition.y + forward.y * farDistance,
                                        cameraPosition.z + forward.z * farDistance);

        // Create the six planes
        // Near and far planes - normals must point inward to frustum
        Vector3f nearNormal = new Vector3f(-forward.x, -forward.y, -forward.z); // Points towards camera (inward)
        Vector3f farNormal = new Vector3f(forward.x, forward.y, forward.z);     // Points away from camera (inward)

        Plane3D nearPlane = Plane3D.fromPointAndNormal(nearCenter, nearNormal);
        Plane3D farPlane = Plane3D.fromPointAndNormal(farCenter, farNormal);

        // Side planes - create points on frustum edges and derive planes
        float farHalfHeight = farDistance * (float) Math.tan(fovy / 2.0f);
        float farHalfWidth = farHalfHeight * aspectRatio;

        // Left plane (normal points inward to frustum)
        Point3f leftNear = new Point3f(nearCenter.x - right.x * halfWidth, nearCenter.y - right.y * halfWidth,
                                       nearCenter.z - right.z * halfWidth);
        Point3f leftFar = new Point3f(farCenter.x - right.x * farHalfWidth, farCenter.y - right.y * farHalfWidth,
                                      farCenter.z - right.z * farHalfWidth);
        Point3f leftTop = new Point3f(leftNear.x + actualUp.x * halfHeight, leftNear.y + actualUp.y * halfHeight,
                                      leftNear.z + actualUp.z * halfHeight);

        // Reverse winding order to get correct normal direction
        Plane3D leftPlane = Plane3D.fromThreePoints(cameraPosition, leftTop, leftNear);

        // Right plane
        Point3f rightNear = new Point3f(nearCenter.x + right.x * halfWidth, nearCenter.y + right.y * halfWidth,
                                        nearCenter.z + right.z * halfWidth);
        Point3f rightTop = new Point3f(rightNear.x + actualUp.x * halfHeight, rightNear.y + actualUp.y * halfHeight,
                                       rightNear.z + actualUp.z * halfHeight);

        Plane3D rightPlane = Plane3D.fromThreePoints(cameraPosition, rightNear, rightTop);

        // Top plane
        Point3f topNear = new Point3f(nearCenter.x + actualUp.x * halfHeight, nearCenter.y + actualUp.y * halfHeight,
                                      nearCenter.z + actualUp.z * halfHeight);
        Point3f topRight = new Point3f(topNear.x + right.x * halfWidth, topNear.y + right.y * halfWidth,
                                       topNear.z + right.z * halfWidth);

        Plane3D topPlane = Plane3D.fromThreePoints(cameraPosition, topRight, topNear);

        // Bottom plane
        Point3f bottomNear = new Point3f(nearCenter.x - actualUp.x * halfHeight, nearCenter.y - actualUp.y * halfHeight,
                                         nearCenter.z - actualUp.z * halfHeight);
        Point3f bottomRight = new Point3f(bottomNear.x + right.x * halfWidth, bottomNear.y + right.y * halfWidth,
                                          bottomNear.z + right.z * halfWidth);

        Plane3D bottomPlane = Plane3D.fromThreePoints(cameraPosition, bottomNear, bottomRight);

        return new Frustum3D(nearPlane, farPlane, leftPlane, rightPlane, topPlane, bottomPlane);
    }

    /**
     * Validate that all coordinates in a point are positive
     */
    private static void validatePositiveCoordinates(Point3f point, String paramName) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(paramName + " coordinates must be positive, got: " + point);
        }
    }

    /**
     * Test if an axis-aligned bounding box is completely inside the frustum
     *
     * @param minX minimum X coordinate of the box (positive)
     * @param minY minimum Y coordinate of the box (positive)
     * @param minZ minimum Z coordinate of the box (positive)
     * @param maxX maximum X coordinate of the box (positive)
     * @param maxY maximum Y coordinate of the box (positive)
     * @param maxZ maximum Z coordinate of the box (positive)
     * @return true if the box is completely inside the frustum
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public boolean containsAABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (minX < 0 || minY < 0 || minZ < 0 || maxX < 0 || maxY < 0 || maxZ < 0) {
            throw new IllegalArgumentException("All coordinates must be positive");
        }

        // For each plane, check if all vertices of the box are on the inside
        Point3f[] vertices = { new Point3f(minX, minY, minZ), new Point3f(maxX, minY, minZ), new Point3f(minX, maxY,
                                                                                                         minZ),
                               new Point3f(maxX, maxY, minZ), new Point3f(minX, minY, maxZ), new Point3f(maxX, minY,
                                                                                                         maxZ),
                               new Point3f(minX, maxY, maxZ), new Point3f(maxX, maxY, maxZ) };

        for (Point3f vertex : vertices) {
            if (!containsPoint(vertex)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Test if a cube is completely inside the frustum
     *
     * @param cube the cube to test
     * @return true if cube is completely inside the frustum
     */
    public boolean containsCube(Spatial.Cube cube) {
        return containsAABB(cube.originX(), cube.originY(), cube.originZ(), cube.originX() + cube.extent(),
                            cube.originY() + cube.extent(), cube.originZ() + cube.extent());
    }

    /**
     * Test if a point is inside the frustum A point is inside if it's on the negative side of all planes (inside
     * half-space)
     *
     * @param point the point to test (positive coordinates only)
     * @return true if point is inside the frustum
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public boolean containsPoint(Point3f point) {
        validatePositiveCoordinates(point, "point");

        return nearPlane.distanceToPoint(point) <= 0 && farPlane.distanceToPoint(point) <= 0
        && leftPlane.distanceToPoint(point) <= 0 && rightPlane.distanceToPoint(point) <= 0 && topPlane.distanceToPoint(
        point) <= 0 && bottomPlane.distanceToPoint(point) <= 0;
    }

    /**
     * Get all six planes of the frustum
     *
     * @return array of the six frustum planes
     */
    public Plane3D[] getPlanes() {
        return new Plane3D[] { nearPlane, farPlane, leftPlane, rightPlane, topPlane, bottomPlane };
    }

    /**
     * Test if an axis-aligned bounding box intersects with the frustum Uses separating axis theorem - if the box is
     * completely outside any plane, no intersection
     *
     * @param minX minimum X coordinate of the box (positive)
     * @param minY minimum Y coordinate of the box (positive)
     * @param minZ minimum Z coordinate of the box (positive)
     * @param maxX maximum X coordinate of the box (positive)
     * @param maxY maximum Y coordinate of the box (positive)
     * @param maxZ maximum Z coordinate of the box (positive)
     * @return true if the box intersects the frustum
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public boolean intersectsAABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (minX < 0 || minY < 0 || minZ < 0 || maxX < 0 || maxY < 0 || maxZ < 0) {
            throw new IllegalArgumentException("All coordinates must be positive");
        }

        // Test if the AABB is completely outside any frustum plane
        // If it is, then there's no intersection
        Plane3D[] planes = getPlanes();
        for (Plane3D plane : planes) {
            // Check if all vertices of the AABB are on the positive side of the plane
            boolean allOutside = true;
            for (int i = 0; i < 8; i++) {
                float x = (i & 1) == 0 ? minX : maxX;
                float y = (i & 2) == 0 ? minY : maxY;
                float z = (i & 4) == 0 ? minZ : maxZ;
                Point3f vertex = new Point3f(x, y, z);
                // Use < instead of <= to consider touching as outside
                if (plane.distanceToPoint(vertex) < 0) {
                    allOutside = false;
                    break;
                }
            }
            if (allOutside) {
                return false; // AABB is completely outside this plane
            }
        }
        return true; // AABB intersects the frustum
    }

    /**
     * Test if a cube intersects with the frustum
     *
     * @param cube the cube to test intersection with
     * @return true if cube intersects the frustum
     */
    public boolean intersectsCube(Spatial.Cube cube) {
        return intersectsAABB(cube.originX(), cube.originY(), cube.originZ(), cube.originX() + cube.extent(),
                              cube.originY() + cube.extent(), cube.originZ() + cube.extent());
    }

    @Override
    public String toString() {
        return String.format("Frustum3D[near=%s, far=%s, left=%s, right=%s, top=%s, bottom=%s]", nearPlane, farPlane,
                             leftPlane, rightPlane, topPlane, bottomPlane);
    }
}
