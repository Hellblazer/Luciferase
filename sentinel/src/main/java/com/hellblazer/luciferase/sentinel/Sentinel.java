/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sentinel;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import javax.vecmath.Point3i;
import javax.vecmath.Tuple3i;

import org.davidmoten.hilbert.HilbertCurve;

import com.github.davidmoten.guavamini.Lists;
import com.github.davidmoten.guavamini.Preconditions;
import com.github.davidmoten.guavamini.annotations.VisibleForTesting;

/**
 * Kinetic point cloud tracking
 *
 * @author hal.hildebrand
 */
public class Sentinel {
    public static class OutOfBoundsException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Tuple3i     destination;
        private final Tuple3i     extent;

        public OutOfBoundsException(String message, Tuple3i destination, Tuple3i extent) {
            super(message);
            this.destination = new Point3i(destination);
            this.extent = new Point3i(extent);
        }

        public Tuple3i getDestination() {
            return destination;
        }

        public Tuple3i getExtent() {
            return extent;
        }
    }

    final static class Box {
        @VisibleForTesting
        static void addOne(long[] x, long[] mins, long[] maxes) {
            for (int i = x.length - 1; i >= 0; i--) {
                if (x[i] != maxes[i]) {
                    x[i]++;
                    break;
                } else {
                    x[i] = mins[i];
                }
            }
        }

        @VisibleForTesting
        static boolean equals(long[] a, long[] b) {
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            return true;
        }

        @VisibleForTesting
        static void visitPerimeter(long[] mins, long[] maxes, long[] x, int specialIndex,
                                   Consumer<? super long[]> visitor) {
            long[] y = Arrays.copyOf(x, x.length);
            for (int i = specialIndex + 1; i < y.length; i++) {
                if (mins[i] >= maxes[i] - 1) {
                    return;
                }
                y[i] = mins[i] + 1;
            }
            visitor.accept(y);
            while (true) {
                // try to increment once
                for (int i = y.length - 1; i >= 0; i--) {
                    if (i > specialIndex) {
                        // to the right of specialIndex we only allow values between min + 1 and max -1
                        // inclusive
                        if (y[i] == maxes[i] - 1) {
                            y[i] = mins[i] + 1;
                            // continue looping to increment at the next index to the left
                        } else {
                            // increment happened without carryover so we break and report y
                            y[i] += 1;
                            break;
                        }
                    } else if (i < specialIndex) {
                        // to the left of specialIndex we allow all values
                        if (y[i] == maxes[i]) {
                            if (i == 0) {
                                return;
                            } else {
                                y[i] = mins[i];
                            }
                        } else {
                            y[i] += 1;
                            break;
                        }
                    } else if (i == specialIndex && i == 0) {
                        return;
                    }
                }
                visitor.accept(y);
            }
        }

        private static long[] maxes(long[] a, long[] b) {
            long[] c = new long[a.length];
            for (int i = 0; i < a.length; i++) {
                c[i] = Math.max(a[i], b[i]);
            }
            return c;
        }

        private static long[] mins(long[] a, long[] b) {
            long[] c = new long[a.length];
            for (int i = 0; i < a.length; i++) {
                c[i] = Math.min(a[i], b[i]);
            }
            return c;
        }

        final long[] a;

        final long[] b;

        Box(long[] a, long[] b) {
            Preconditions.checkArgument(a.length == b.length);
            this.a = a;
            this.b = b;
        }

        @Override
        public String toString() {
            return "Box [" + Arrays.toString(a) + ", " + Arrays.toString(b) + "]";
        }

        boolean contains(long[] point) {
            Preconditions.checkArgument(a.length == point.length);
            for (int i = 0; i < a.length; i++) {
                if (point[i] < Math.min(a[i], b[i]) || point[i] > Math.max(a[i], b[i])) {
                    return false;
                }
            }
            return true;
        }

        int dimensions() {
            return a.length;
        }

        void visitCells(Consumer<? super long[]> visitor) {
            long[] mins = mins(a, b);
            long[] maxes = maxes(a, b);
            long[] x = Arrays.copyOf(mins, mins.length);
            while (true) {
                visitor.accept(x);
                if (equals(x, maxes)) {
                    break;
                } else {
                    addOne(x, mins, maxes);
                }
            }
        }

        void visitPerimeter(Consumer<? super long[]> visitor) {
            long[] mins = mins(a, b);
            long[] maxes = maxes(a, b);
            for (int specialIndex = dimensions() - 1; specialIndex >= 0; specialIndex--) {
                long[] x = Arrays.copyOf(mins, mins.length);
                // visit for the minimum at specialIndex
                visitPerimeter(mins, maxes, x, specialIndex, visitor);
                if (mins[specialIndex] != maxes[specialIndex]) {
                    // visit for the maximum at specialIndex
                    long[] y = Arrays.copyOf(mins, mins.length);
                    y[specialIndex] = maxes[specialIndex];
                    visitPerimeter(mins, maxes, y, specialIndex, visitor);
                } else {
                    break;
                }
            }
        }
    }

    final static class Node implements Comparable<Node> {
        final Range        value;
        private BigInteger distanceToPrevious = BigInteger.valueOf(0);
        private Node       next;
        private Node       previous;

        Node(Range value) {
            this.value = value;
        }

        @Override
        public int compareTo(Node o) {
            if (this == o) {
                return 0;
            } else {
                if (next == null) {
                    return -1;
                }
                var x = distanceToPrevious;
                var y = o.distanceToPrevious;
                final var c = x.compareTo(y);
                if (c < 0) {
                    return -1;
                } else if (c == 0) {
                    return value.low().compareTo(o.value.low());
                } else {
                    return 1;
                }
            }
        }

        @Override
        public String toString() {
            return "Node [value=" + value + ", next=" + next + ", previous=" + previous + "]";
        }

        void clearForGc() {
            next = null;
            previous = null;
        }

        Node next() {
            return next;
        }

        Node previous() {
            return previous;
        }

        void setDistanceToPrevious(BigInteger distance) {
            this.distanceToPrevious = distance;
        }

        Node setNext(Node next) {
            Preconditions.checkNotNull(next);
            Preconditions.checkArgument(next != this);
            this.next = next;
            next.distanceToPrevious = value.low().subtract(next.value.high());
            next.previous = this;
            return this;
        }

    }

    final static class Range {
        public static Range create(BigInteger value) {
            return new Range(value, value);
        }

        public static Range create(BigInteger low, BigInteger high) {
            return new Range(low, high);
        }

        private final BigInteger high;

        private final BigInteger low;

        public Range(BigInteger low, BigInteger high) {
            this.low = low.min(high);
            this.high = low.max(high);
        }

        public boolean contains(BigInteger value) {
            return low.compareTo(value) <= 0 && value.compareTo(high) <= 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Range other = (Range) obj;
            if (!high.equals(other.high))
                return false;
            if (!low.equals(other.low))
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(high, low);
        }

        public BigInteger high() {
            return high;
        }

        public Range join(Range range) {
            return Range.create(low.min(range.low), high.max(range.high));
        }

        public BigInteger low() {
            return low;
        }

        @Override
        public String toString() {
            return "Range [low=" + low + ", high=" + high + "]";
        }

    }

    static class Ranges implements Iterable<Range> {
        private final int           bufferSize;
        private int                 count;
        private Node                last;
        private Node                ranges;
        private final TreeSet<Node> set;

        public Ranges(int bufferSize) {
            Preconditions.checkArgument(bufferSize >= 0);
            this.bufferSize = bufferSize;
            this.ranges = null;
            if (bufferSize == 0) {
                // save on allocations
                this.set = null;
            } else {
                this.set = new TreeSet<>();
            }
        }

        public Ranges add(BigInteger low, BigInteger high) {
            Preconditions.checkArgument(low.compareTo(high) <= 0);
            return add(Range.create(low, high));
        }

        public Ranges add(Range r) {
            Preconditions.checkArgument(ranges == null || ranges.value.high().compareTo(r.low()) < 0,
                                        "ranges must be added in increasing order and without overlap");
            Node node = new Node(r);
            count++;
            if (ranges == null) {
                ranges = node;
                last = node;
            } else if (bufferSize == 0) {
                node.setNext(ranges);
                ranges = node;
            } else {
                // and set new head and recalculate distance for ranges
                node.setNext(ranges);

                // add old head to set (now that the distanceToPrevious has been calculated)
                set.add(ranges);

                ranges = node;

                if (count > bufferSize) {
                    // remove node from set with least distance to next node
                    Node first = set.pollFirst();

                    // replace that node in linked list (ranges) with a new Node
                    // that has the concatenation of that node with previous node's range
                    // also remove its predecessor. We dont' need to remove the predecessor from the
                    // set because it's distanceToPrevious will remain the same

                    // first.previous will not be null because distance was present to be in set
                    Range joined = first.value.join(first.previous().value);
                    set.remove(first.previous());

                    Node n = new Node(joined);
                    // link and recalculate distance (won't change because the lower bound of the
                    // new ranges is the same as the lower bound of the range of first)
                    if (first.next() != null) {
                        n.setNext(first.next());
                    } else {
                        // first is last in linked list so update last
                        last = n;
                    }
                    // link and calculate the distance for n
                    Node firstPrevious = first.previous();
                    if (firstPrevious == ranges) {
                        ranges = n;
                    } else {
                        first.previous().previous().setNext(n);
                    }
                    set.add(n);

                    // clear pointers from first to help gc out
                    // there new gen to old gen promotion can cause problems
                    first.clearForGc();

                    // we have reduced number of nodes in list so reduce count
                    count--;
                }
            }
            return this;
        }

        @Override
        public Iterator<Range> iterator() {
            return new Iterator<Range>() {

                Node r = last;

                @Override
                public boolean hasNext() {
                    return r != null;
                }

                @Override
                public Range next() {
                    Range v = r.value;
                    r = r.previous();
                    return v;
                }

            };
        }

        public int size() {
            return count;
        }

        public List<Range> toList() {
            return Lists.newArrayList(this);
        }

        @Override
        public String toString() {
            return toList().toString();
        }

    }

    public static final int         MAX                 = 65536;
    private static final int        BITS                = 49;
    private static final int        DEFAULT_BUFFER_SIZE = 1024;
    private static final BigInteger MINUS_1             = BigInteger.valueOf(-1);
    private static final BigInteger PLUS_1              = BigInteger.valueOf(1);

    /**
     * Returns index ranges exactly covering the region bounded by {@code a} and
     * {@code b}. The list will be in increasing order of the range bounds (there
     * should be no overlaps).
     * 
     * @param a one vertex of the region
     * @param b the opposing vertex to a
     * @return ranges
     */
    static Ranges query(long[] a, long[] b, HilbertCurve curve) {
        return query(a, b, 0, 0, curve);
    }

    /**
     * Returns index ranges covering the region bounded by {@code a} and {@code b}.
     * The list will be in increasing order of the range bounds (there should be no
     * overlaps). The index ranges may cover a larger region than the search box
     * because the set of exact covering ranges will have been reduced by joining
     * ranges with minimal gaps. The buffer size used by this method is 1024.
     * 
     * @param a         one vertex of the region
     * @param b         the opposing vertex to a
     * @param maxRanges the maximum number of ranges to be returned. If 0 then all
     *                  ranges are returned.
     * @return ranges
     */
    static Ranges query(long[] a, long[] b, int maxRanges, HilbertCurve curve) {
        if (maxRanges == 0) {
            return query(a, b, 0, 0, curve);
        } else {
            return query(a, b, maxRanges, Math.max(DEFAULT_BUFFER_SIZE, maxRanges), curve);
        }
    }

    /**
     * Returns index ranges covering the region bounded by {@code a} and {@code b}.
     * The list will be in increasing order of the range bounds (there should be no
     * overlaps). The index ranges may cover a larger region than the search box
     * because the set of exact covering ranges will have been reduced by joining
     * ranges with minimal gaps. A buffer of ranges is used and has a size that is
     * generally speaking a fair bit larger than maxRanges to increase the
     * probability that minimal extra coverage be returned.
     * 
     * @param a          one vertex of the region
     * @param b          the opposing vertex to a
     * @param maxRanges  the maximum number of ranges to be returned. If 0 then all
     *                   ranges are returned.
     * @param bufferSize the buffer size of ranges to use. A larger buffer size will
     *                   increase the probability that ranges have been joined
     *                   optimally (we want to join ranges that have minimal gaps to
     *                   minimize the resultant extra coverage). If 0 is passed to
     *                   bufferSize then all ranges will be buffered before
     *                   shrinking to maxRanges.
     * @return ranges
     */
    static Ranges query(long[] a, long[] b, int maxRanges, int bufferSize, HilbertCurve curve) {
        Preconditions.checkArgument(maxRanges >= 0);
        Preconditions.checkArgument(bufferSize >= maxRanges, "bufferSize must be greater than or equal to maxRanges");
        if (maxRanges == 0) {
            // unlimited
            bufferSize = 0;
        }
        // this is the implementation of the Perimeter Algorithm mentioned in README.md

        Box box = new Box(a, b);
        var list = new ArrayList<BigInteger>();
        box.visitPerimeter(cell -> {
            var n = curve.index(cell);
            list.add(n);
        });
        Collections.sort(list);
        int i = 0;
        var ranges = new Ranges(bufferSize);
        var rangeStart = MINUS_1;
        while (true) {
            if (i == list.size()) {
                break;
            }
            if (rangeStart.equals(MINUS_1)) {
                rangeStart = list.get(i);
            }
            while (i < list.size() - 1 && list.get(i + 1).equals(list.get(i).add(PLUS_1))) {
                i++;
            }
            if (i == list.size() - 1) {
                ranges.add(Range.create(rangeStart, list.get(i)));
                break;
            }
            long[] point = curve.point(list.get(i).add(PLUS_1));
            if (box.contains(point)) {
                // is not on the perimeter (would have been caught in previous while loop)
                // so is internal to the box which means the next value in the sorted hilbert
                // curve indexes for the perimiter must be where it exits
                i += 1;
            } else {
                ranges.add(Range.create(rangeStart, list.get(i)));
                rangeStart = MINUS_1;
                i++;
            }
        }
        if (ranges.size() <= maxRanges) {
            return ranges;
        } else {
            Ranges r = new Ranges(maxRanges);
            for (Range range : ranges) {
                r.add(range);
            }
            return r;
        }
    }

    private final HilbertCurve                   curve;
    private final Point3i                        extent;
    private final NavigableMap<BigInteger, Site> tracking = new TreeMap<>();

    public Sentinel() {
        curve = HilbertCurve.bits(BITS).dimensions(3);
        extent = new Point3i(MAX, MAX, MAX);
    }

    /**
     * @return the maximum extent of the tracked volume
     */
    public Point3i getExtent() {
        return extent;
    }

    /**
     * Move the site by the delta. If the site exits the tracking volume, the site
     * is no longer tracked by the receiver
     *
     * @param site
     * @param delta
     */
    public void moveBy(Site site, Tuple3i delta) {
        var removed = tracking.remove(site.getHilbert());
        assert removed == site : "Site is not tracked: %s:%s found: %s:%s".formatted(site, site.getHilbert(), removed,
                                                                                     removed.getHilbert());
        site.add(delta);
        update(site);
    }

    /**
     * Move the site to the new location
     *
     * @param site
     * @param newLocation
     */
    public void moveTo(Site site, Tuple3i newLocation) {
        var removed = tracking.remove(site.getHilbert());
        assert removed == site : "Site is not tracked: %s".formatted(site);
        site.set(newLocation);
        update(site);
    }

    /**
     * 
     * @param q - the query point
     * @param k - maximum number of nearest neighbors to return
     * @param a - the first corner of the query box
     * @param b - the second corner of the query box
     * @return Answer up to k nearest neighbors of the site within the box defined
     *         by the corners a and b
     */
    public List<Site> nn(Tuple3i q, int k, Tuple3i a, Tuple3i b) {
        var pq = new PriorityQueue<Site>(Comparator.comparing(s -> {
            final var distance = new Vector3i(q);
            distance.sub(s);
            return distance.lengthSquared();
        }));
        var pA = new long[] { a.x, a.y, a.z };
        var pB = new long[] { b.x, b.y, b.z };
        final var ranges = query(pA, pB, curve);
        var neighbors = new ArrayList<Site>();
        for (var range : ranges) {
            final var head = tracking.headMap(range.high, true);
            if (!head.isEmpty()) {
                if (head.firstKey().compareTo(range.low()) <= 0) {
                    head.tailMap(range.low, true).forEach((i, s) -> {
                        pq.add(s);
                    });
                }
            }
        }
        while (neighbors.size() < k && !pq.isEmpty()) {
            neighbors.add(pq.remove());
        }
        return neighbors;
    }

    /**
     * Track as site from the initial location
     *
     * @param initial - the initial location of the site
     * @return the Site tracked starting at the initial location
     * @throws OutOfBoundsException if the initial point is not positive and <= the
     *                              max extent of the receiver
     */
    public Site track(Tuple3i initial) throws OutOfBoundsException {
        if (!valid(initial)) {
            throw new OutOfBoundsException("Cannot track out of bounds: " + initial, initial, extent);
        }
        final var hilbert = curve.index(initial.x, initial.y, initial.z);
        Site s = new Site(initial, hilbert);
        tracking.put(hilbert, s);
        return s;
    }

    private void update(Site site) {
        if (!valid(site)) {
            return;
        }
        site.setHilbert(curve.index(site.x, site.y, site.z));
        tracking.put(site.getHilbert(), site);
    }

    private boolean valid(Tuple3i initial) {
        return initial.x >= 0 || initial.x <= extent.x && initial.y >= 0 || initial.y <= extent.y && initial.z >= 0 ||
               initial.z <= extent.z;
    }
}
