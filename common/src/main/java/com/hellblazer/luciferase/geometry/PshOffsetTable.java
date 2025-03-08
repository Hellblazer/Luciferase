package com.hellblazer.luciferase.geometry;

import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Created by Skinner on 8/9/14. This class is used to create perfect spatial hashing for a set of 3D indices. This
 * class takes as input a list of 3d index (3D integer vector), and creates a mapping that can be used to pair a 3d
 * index with a value. The best part about this type of hash map is that it can compress 3d spatial data in such a way
 * that there spatial coherency (3d indices near each other have paired values near each other in the hash table) and
 * the lookup time is O(1). Since it's perfect hashing there is no hash collisions This hashmap could be used for a very
 * efficient hash table on the GPU due to coherency and only 2 lookups from a texture-hash-table would be needed, one
 * for the offset to help create the hash, and one for the actual value indexed by the final hash. This implementation
 * is based off the paper: <a href=http://hhoppe.com/perfecthash.pdf>Perfect Spatial Hashing by Sylvain Lefebvre &Hugues
 * Hopp, Microsoft Research</a>
 * <p/>
 * To use: accumulate your spatial data in a list to pass to the PshOffsetTable class construct the table with the list
 * you now use this class just as your "mapping", it has the hash function for your hash table create your 3D hash with
 * the chosen width from PshOffsetTable.hashTableWidth. Then to get the index into your hash table, just use
 * PshOffsetTable.hash(key). That's it.
 * <p/>
 * If you want to update the offset table, you can do so by using the updateOffsets() with the modified list of spatial
 * data.
 */

public class PshOffsetTable {

    private static final int                tableCreateLimit = 10;
    private final        Random             entropy;
    private              Point3i[][][]      offsetTable;
    private              int                offsetTableWidth;
    private              int                hashTableWidth;
    private              int                n;
    private              ArrayList<Point3i> elements;
    private              OffsetBucket[][][] offsetBuckets;
    private              boolean[][][]      hashFilled;
    private              int                creationAttempts = 0;

    public PshOffsetTable(ArrayList<Point3i> elements, Random entropy) {
        this.entropy = entropy;
        int size = elements.size();
        n = size;
        hashTableWidth = calcHashTableWidth(size);
        offsetTableWidth = calcOffsetTableWidth(size);

        hashFilled = new boolean[hashTableWidth][hashTableWidth][hashTableWidth];
        offsetBuckets = new OffsetBucket[offsetTableWidth][offsetTableWidth][offsetTableWidth];

        offsetTable = new Point3i[offsetTableWidth][offsetTableWidth][offsetTableWidth];
        clearOffsstsToZero();
        this.elements = elements;

        calculateOffsets();

        cleanUp();

    }

    public static int gcd(int a, int b) {
        if (a == 0 || b == 0) {
            return a + b; // base case
        }
        return gcd(b, a % b);
    }

    private static Point3i mod(Point3i lhs, int rhs) {
        var answer = new Point3i((lhs.x % rhs), (lhs.y % rhs), (lhs.z % rhs));
        answer.x += rhs;
        answer.y += rhs;
        answer.z += rhs;

        answer.x %= rhs;
        answer.y %= rhs;
        answer.z %= rhs;
        return answer;
    }

    public Point3i hash(Point3i key) {
        var index = hash1(key);
        var hashed = hash0(key);
        hashed.add(offsetTable[index.x][index.y][index.z]);
        return hash0(hashed);
    }

    public void updateOffsets(ArrayList<Point3i> elements) {
        int size = elements.size();
        n = size;
        hashTableWidth = calcHashTableWidth(size);
        int oldOffsetWidth = offsetTableWidth;
        offsetTableWidth = calcOffsetTableWidth(
        size); //this breaks if original creation didn't use initial table calculated width

        hashFilled = new boolean[hashTableWidth][hashTableWidth][hashTableWidth];
        offsetBuckets = new OffsetBucket[offsetTableWidth][offsetTableWidth][offsetTableWidth];

        this.elements = elements;

        if (oldOffsetWidth != offsetTableWidth) {
            offsetTable = new Point3i[offsetTableWidth][offsetTableWidth][offsetTableWidth];
            clearOffsstsToZero();
            calculateOffsets();

            cleanUp();
        } else {

            putElementsIntoBuckets();
            List<OffsetBucket> bucketList = createSortedBucketList();

            for (OffsetBucket bucket : bucketList) {
                var offset = offsetTable[bucket.index.x][bucket.index.y][bucket.index.z];
                if (!OffsetWorks(bucket, offset)) {
                    offset = findOffsetRandom(bucket);
                    if (offset == null) {
                        tryCreateAgain();
                        break;
                    }
                    offsetTable[bucket.index.x][bucket.index.y][bucket.index.z] = offset;
                }
                fillHashCheck(bucket, offset);
                //            if(checkForBadCollisions(bucket)) {
                //                tryCreateAgain();
                //                break;
                //            }
            }
        }

    }

    private boolean OffsetWorks(OffsetBucket bucket, Point3i offset) {
        for (int i = 0; i < bucket.contents.size(); i++) {
            var ele = bucket.contents.get(i);
            var hash = hash(ele, offset);
            if (hashFilled[hash.x][hash.y][hash.z]) {
                return false;
            }
        }
        return true;
    }

    private int calcHashTableWidth(int size) {
        float d = (float) Math.pow(size * 1.1, 1.0f / 3);
        return (int) (d + 1.1f);
    }

    private int calcOffsetTableWidth(int size) {
        float d = (float) Math.pow(size >> 2, 1.0f / 3);
        int width = (int) (d + 1.1f);
        while (gcd(width, hashTableWidth) > 1) { //make sure there are no common facctors
            width++;
        }
        return width;

    }

    private void calculateOffsets() {

        putElementsIntoBuckets();
        List<OffsetBucket> bucketList = createSortedBucketList();

        for (OffsetBucket bucket : bucketList) {
            //            if(checkForBadCollisions(bucket)) {
            //                tryCreateAgain();
            //                break;
            //            }
            //Vec3I offset = findOffset(bucket);
            var offset = findOffsetRandom(bucket);

            if (offset == null) {
                tryCreateAgain();
                break;
            }
            offsetTable[bucket.index.x][bucket.index.y][bucket.index.z] = offset;
            fillHashCheck(bucket, offset);

        }

    }

    private boolean checkForBadCollisions(OffsetBucket bucket) {
        var testList = new ArrayList<>(10);
        for (int i = 0; i < bucket.contents.size(); i++) {

            var ele = bucket.contents.get(i);
            var hash = hash0(ele);
            if (testList.contains(hash)) {
                return true;

            } else {
                testList.add(hash);
            }

        }
        return false;

    }

    private void cleanUp() {
        this.elements = null;
        this.offsetBuckets = null;
        this.hashFilled = null;

    }

    private void clearFilled() {
        for (int x = 0; x < hashTableWidth; x++) {
            for (int y = 0; y < hashTableWidth; y++) {
                for (int z = 0; z < hashTableWidth; z++) {
                    hashFilled[x][y][z] = false;
                }
            }
        }
    }

    private void clearOffsstsToZero() {
        for (int x = 0; x < offsetTableWidth; x++) {
            for (int y = 0; y < offsetTableWidth; y++) {
                for (int z = 0; z < offsetTableWidth; z++) {
                    offsetTable[x][y][z] = new Point3i(0, 0, 0);
                }
            }
        }
    }

    private List<OffsetBucket> createSortedBucketList() {
        List<OffsetBucket> bucketList = new ArrayList<>(
        offsetTableWidth * offsetTableWidth * offsetTableWidth);//(offsetTableWidth*offsetTableWidth*offsetTableWidth);

        for (int x = 0; x < offsetTableWidth; x++) { //put the buckets into the bucketlist and sort
            for (int y = 0; y < offsetTableWidth; y++) {
                for (int z = 0; z < offsetTableWidth; z++) {
                    if (offsetBuckets[x][y][z] != null) {
                        bucketList.add(offsetBuckets[x][y][z]);
                    }
                }
            }
        }
        quicksort(bucketList, 0, bucketList.size() - 1);
        return bucketList;
    }

    private void fillHashCheck(OffsetBucket bucket, Point3i offset) {
        for (int i = 0; i < bucket.contents.size(); i++) {
            var ele = bucket.contents.get(i);
            var hash = hash(ele, offset);
            hashFilled[hash.x][hash.y][hash.z] = true;
        }

    }

    private Point3i findAEmptyHash() {
        var seed = new Point3i(entropy.nextInt(hashTableWidth) - hashTableWidth / 2,
                               entropy.nextInt(hashTableWidth) - hashTableWidth / 2,
                               entropy.nextInt(hashTableWidth) - hashTableWidth / 2);
        for (int x = 0; x < hashTableWidth; x++) {
            for (int y = 0; y < hashTableWidth; y++) {
                for (int z = 0; z < hashTableWidth; z++) {
                    var index = new Point3i(x, y, z);
                    index.add(seed);
                    index = hash0(index);
                    if (!hashFilled[index.x][index.y][index.z]) {
                        return index;
                    }
                }
            }
        }
        return null;
    }

    private Point3i findAEmptyHash(Point3i start) {
        for (int x = 0; x < hashTableWidth; x++) {
            for (int y = 0; y < hashTableWidth; y++) {
                for (int z = 0; z < hashTableWidth; z++) {
                    if (x + y + z == 0) {
                        continue;
                    }
                    var index = new Point3i(x, y, z);
                    index.add(start);
                    index = hash0(index);
                    if (!hashFilled[index.x][index.y][index.z]) {
                        return index;
                    }
                }
            }
        }
        return null;
    }

    private Point3i findOffsetRandom(OffsetBucket bucket) {

        var badOffests = new ArrayList<Point3i>();
        var offset = new Point3i(0, 0, 0);
        var index = new Point3i(1, 0, 0);
        index.add(bucket.index);
        index = hash1(index);
        offset = offsetTable[index.x][index.y][index.z];
        if (!badOffests.contains(offset)) {
            if (OffsetWorks(bucket, offset)) {
                return offset;
            }
            badOffests.add(offset);
        }
        index = new Point3i(0, 1, 0);
        index.add(bucket.index);
        index = hash1(index);
        offset = offsetTable[index.x][index.y][index.z];
        if (!badOffests.contains(offset)) {
            if (OffsetWorks(bucket, offset)) {
                return offset;
            }
            badOffests.add(offset);
        }
        index = new Point3i(0, 0, 1);
        index.add(bucket.index);
        index = hash1(index);
        offset = offsetTable[index.x][index.y][index.z];
        if (!badOffests.contains(offset)) {
            if (OffsetWorks(bucket, offset)) {
                return offset;
            }
            badOffests.add(offset);
        }
        index = new Point3i(-1, 0, 0);
        index.add(bucket.index);
        index = hash1(index);
        offset = offsetTable[index.x][index.y][index.z];
        if (!badOffests.contains(offset)) {
            if (OffsetWorks(bucket, offset)) {
                return offset;
            }
            badOffests.add(offset);
        }
        index = new Point3i(0, -1, 0);
        index.add(bucket.index);
        index = hash1(index);
        offset = offsetTable[index.x][index.y][index.z];
        if (!badOffests.contains(offset)) {
            if (OffsetWorks(bucket, offset)) {
                return offset;
            }
            badOffests.add(offset);
        }
        index = new Point3i(0, 0, -1);
        index.add(bucket.index);
        index = hash1(index);
        offset = offsetTable[index.x][index.y][index.z];
        if (!badOffests.contains(offset)) {
            if (OffsetWorks(bucket, offset)) {
                return offset;
            }
            badOffests.add(offset);
        }

        //        Vec3I emptyHash = findAEmptyHash();
        //        offset = Vec3I.subtract(emptyHash , hash0(bucket.contents.get(0)));
        //        if(OffsetWorks(bucket, offset)) return offset;

        Point3i seed = new Point3i(entropy.nextInt(hashTableWidth) - hashTableWidth / 2,
                                   entropy.nextInt(hashTableWidth) - hashTableWidth / 2,
                                   entropy.nextInt(hashTableWidth) - hashTableWidth / 2);
        for (int i = 0; i <= 5; i++) {
            for (int x = i; x < hashTableWidth; x += 5) {
                for (int y = i; y < hashTableWidth; y += 5) {
                    for (int z = i; z < hashTableWidth; z += 5) {
                        index = new Point3i(x, y, z);
                        index.add(seed);
                        index = hash0(index);
                        if (!hashFilled[index.x][index.y][index.z]) {
                            offset = hash0(bucket.contents.getFirst());
                            offset.sub(index);
                            if (OffsetWorks(bucket, offset)) {
                                return offset;
                            }
                        }
                    }
                }
            }
        }
        //        if(bucket.contents.size() >  1) {
        //
        //
        //
        //            for(int attempt = 0; attempt < 1000; attempt++) {
        //
        ////                while(badOffests.contains(offset)) {
        ////                    offset = new Vec3I(random.nextInt(hashTableWidth) - hashTableWidth/2, random.nextInt(hashTableWidth) - hashTableWidth/2, random.nextInt(hashTableWidth) - hashTableWidth/2);
        ////                }
        ////                if(OffsetWorks(bucket, offset)) return offset;
        //                emptyHash = findAEmptyHash(emptyHash);
        //                offset = Vec3I.subtract(emptyHash , hash0(bucket.contents.get(0)));
        //                if(OffsetWorks(bucket, offset)) return offset;
        //                badOffests.add(offset);
        //            }
        //        }
        //        else {
        //            emptyHash = findAEmptyHash();
        //            offset = Vec3I.subtract(emptyHash , hash0(bucket.contents.get(0)));
        //            if(offset == null) {
        //                return offset;
        //            }
        //            return offset;
        //
        //        }

        return null;
    }

    private Point3i hash(Point3i key, Point3i offset) {
        var hashed = hash0(key);
        hashed.add(offset);
        return hash0(hashed);
    }

    private Point3i hash0(Point3i key) {
        return mod(key, hashTableWidth);
    }

    private Point3i hash1(Point3i key) {
        return mod(key, offsetTableWidth);
    }

    private void putElementsIntoBuckets() {
        for (int i = 0; i < n; i++) {
            var ele = elements.get(i);
            var index = hash1(ele);
            OffsetBucket bucket = offsetBuckets[index.x][index.y][index.z];
            if (bucket == null) {
                bucket = new OffsetBucket(new Point3i(index.x, index.y, index.z));
                offsetBuckets[index.x][index.y][index.z] = bucket;
            }
            bucket.contents.add(ele);
        }
    }

    private void quicksort(List<OffsetBucket> bucketList, int start, int end) {
        int i = start;
        int j = end;
        int pivot = bucketList.get(start + (end - start) / 2).contents.size();
        while (i <= j) {
            while (bucketList.get(i).contents.size() > pivot) {
                i++;
            }
            while (bucketList.get(j).contents.size() < pivot) {
                j--;
            }
            if (i <= j) {
                OffsetBucket temp = bucketList.get(i);
                bucketList.set(i, bucketList.get(j));
                bucketList.set(j, temp);
                i++;
                j--;
            }

        }
        if (start < j) {
            quicksort(bucketList, start, j);
        }
        if (i < end) {
            quicksort(bucketList, i, end);
        }
    }

    private void resizeOffsetTable() {
        offsetTableWidth += 5; //test
        while (gcd(offsetTableWidth, hashTableWidth) > 1) {
            offsetTableWidth++;
        }
        offsetBuckets = new OffsetBucket[offsetTableWidth][offsetTableWidth][offsetTableWidth];
        offsetTable = new Point3i[offsetTableWidth][offsetTableWidth][offsetTableWidth];
        clearOffsstsToZero();

    }

    private void tryCreateAgain() {
        creationAttempts++;
        if (creationAttempts >= tableCreateLimit) {
            throw new RuntimeException("this class doesn't fucking work bad fucking code idiot");
        }
        resizeOffsetTable();
        clearFilled();
        calculateOffsets();

    }

    private record OffsetBucket(List<Point3i> contents, Point3i index) {
        private OffsetBucket(Point3i index) {
            this(new ArrayList<>(), index);
        }
    }

}
