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
package com.hellblazer.luciferase.common;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;

/**
 * @author hal.hildebrand
 */
public class KdTree {
    public static class Node {
        private Point3f coords_;
        private Node    left_  = null;
        private Node    right_ = null;

        public Node(Tuple3f p) {
            coords_ = new Point3f(p);
        }

        @Override
        public String toString() {
            return coords_.toString();
        }

        double distance(Node node) {
            return coords_.distance(node.coords_);
        }

        double get(int index) {
            return switch (index) {
            case 0:
                yield coords_.x;
            case 1:
                yield coords_.y;
            case 2:
                yield coords_.z;
            default:
                throw new IllegalArgumentException("Unexpected index: " + index);
            };
        }
    }

    //
    // Java implementation of quickselect algorithm.
    // See https://en.wikipedia.org/wiki/Quickselect
    //
    public static class QuickSelect {
        private static final Random random = new Random();

        public static <T> T select(List<T> list, int n, Comparator<? super T> cmp) {
            return select(list, 0, list.size() - 1, n, cmp);
        }

        public static <T> T select(List<T> list, int left, int right, int n, Comparator<? super T> cmp) {
            for (;;) {
                if (left == right)
                    return list.get(left);
                int pivot = pivotIndex(left, right);
                pivot = partition(list, left, right, pivot, cmp);
                if (n == pivot)
                    return list.get(n);
                else if (n < pivot)
                    right = pivot - 1;
                else
                    left = pivot + 1;
            }
        }

        private static <T> int partition(List<T> list, int left, int right, int pivot, Comparator<? super T> cmp) {
            T pivotValue = list.get(pivot);
            swap(list, pivot, right);
            int store = left;
            for (int i = left; i < right; ++i) {
                if (cmp.compare(list.get(i), pivotValue) < 0) {
                    swap(list, store, i);
                    ++store;
                }
            }
            swap(list, right, store);
            return store;
        }

        private static int pivotIndex(int left, int right) {
            return left + random.nextInt(right - left + 1);
        }

        private static <T> void swap(List<T> list, int i, int j) {
            T value = list.get(i);
            list.set(i, list.get(j));
            list.set(j, value);
        }
    }

    private static class NodeComparator implements Comparator<Node> {
        private int index_;

        private NodeComparator(int index) {
            index_ = index;
        }

        @Override
        public int compare(Node n1, Node n2) {
            return Double.compare(n1.get(index_), n2.get(index_));
        }
    }

    private Node   best_         = null;
    private double bestDistance_ = 0;

    private int dimensions_;

    private Node root_ = null;

    private int visited_ = 0;

    public KdTree(int dimensions, List<Node> nodes) {
        dimensions_ = dimensions;
        root_ = makeTree(nodes, 0, nodes.size(), 0);
    }

    public double distance() {
        return Math.sqrt(bestDistance_);
    }

    public Node findNearest(Node target) {
        if (root_ == null)
            throw new IllegalStateException("Tree is empty!");
        best_ = null;
        visited_ = 0;
        bestDistance_ = 0;
        nearest(root_, target, 0);
        return best_;
    }

    public int visited() {
        return visited_;
    }

    private Node makeTree(List<Node> nodes, int begin, int end, int index) {
        if (end <= begin)
            return null;
        int n = begin + (end - begin) / 2;
        Node node = QuickSelect.select(nodes, begin, end - 1, n, new NodeComparator(index));
        index = (index + 1) % dimensions_;
        node.left_ = makeTree(nodes, begin, n, index);
        node.right_ = makeTree(nodes, n + 1, end, index);
        return node;
    }

    private void nearest(Node root, Node target, int index) {
        if (root == null)
            return;
        ++visited_;
        double d = root.distance(target);
        if (best_ == null || d < bestDistance_) {
            bestDistance_ = d;
            best_ = root;
        }
        if (bestDistance_ == 0)
            return;
        double dx = root.get(index) - target.get(index);
        index = (index + 1) % dimensions_;
        nearest(dx > 0 ? root.left_ : root.right_, target, index);
        if (dx * dx >= bestDistance_)
            return;
        nearest(dx > 0 ? root.right_ : root.left_, target, index);
    }
}
