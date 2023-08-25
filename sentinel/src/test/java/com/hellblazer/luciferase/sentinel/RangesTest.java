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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.davidmoten.hilbert.HilbertCurve;
import org.junit.jupiter.api.Test;

import com.github.davidmoten.guavamini.Lists;
import com.hellblazer.luciferase.sentinel.Sentinel.Range;
import com.hellblazer.luciferase.sentinel.Sentinel.Ranges;

/**
 * @author hal.hildebrand
 */

public class RangesTest {

    @Test
    public void testIssue5() {
        var c = HilbertCurve.bits(5).dimensions(2);
        long[] point1 = new long[] { 3, 3 };
        long[] point2 = new long[] { 8, 10 };
        // return just one range
        {
            int maxRanges = 1;
            Ranges ranges = Sentinel.query(point1, point2, maxRanges, maxRanges, c);
            checkIs(ranges, 10, 229);
        }
        {
            int maxRanges = 2;
            Ranges ranges = Sentinel.query(point1, point2, maxRanges, c);
            checkIs(ranges, 10, 10, 26, 229);
        }
    }

    @Test
    public void testMaxSizeExceededWhenMaxIs2() {
        Ranges r = new Ranges(2);
        r.add(Range.create(BigInteger.valueOf(1)));
        r.add(Range.create(BigInteger.valueOf(10)));
        r.add(Range.create(BigInteger.valueOf(12)));
        checkIs(r, 1, 1, 10, 12);
        assertEquals(2, r.size());
    }

    @Test
    public void testMaxSizeExceededWhenMaxIs3() {
        Ranges r = new Ranges(3);
        r.add(Range.create(BigInteger.valueOf(1)));
        r.add(Range.create(BigInteger.valueOf(10)));
        r.add(Range.create(BigInteger.valueOf(12)));
        r.add(Range.create(BigInteger.valueOf(18)));
        System.out.println();
        checkIs(r, 1, 1, 10, 12, 18, 18);
    }

    @Test
    public void testMaxSizeNotExceededWhenMaxIs2() {
        Ranges r = new Ranges(2);
        r.add(Range.create(BigInteger.valueOf(1)));
        r.add(Range.create(BigInteger.valueOf(10)));
        checkIs(r, 1, 1, 10, 10);
        assertEquals(2, r.size());
    }

    private void checkIs(Ranges r, int... ords) {
        List<Range> list = new ArrayList<>();
        for (int i = 0; i < ords.length; i += 2) {
            list.add(Range.create(BigInteger.valueOf(ords[i]), BigInteger.valueOf(ords[i + 1])));
        }
        assertEquals(list, Lists.newArrayList(r));
    }
}
