/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.codec.http3;

import org.junit.jupiter.api.Test;

import static io.netty.incubator.codec.http3.QpackUtil.MAX_HEADER_TABLE_SIZE;
import static java.lang.Math.toIntExact;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class QpackEncoderDynamicTableTest {
    private static final QpackHeaderField emptyHeader = new QpackHeaderField("", "");
    private static final QpackHeaderField fooBarHeader = new QpackHeaderField("foo", "bar");
    private static final QpackHeaderField fooBar2Header = new QpackHeaderField("foo", "bar2");
    private static final QpackHeaderField fooBar3Header = new QpackHeaderField("foo", "bar3");

    private int insertCount;
    private long maxCapacity;

    @Test
    public void zeroCapacityIsAllowed() throws Exception {
        QpackEncoderDynamicTable table = newDynamicTable(0);

        assertThat(addHeader(table, emptyHeader)).isLessThan(0);
    }

    @Test
    public void maxCapacityIsAllowed() throws Exception {
        final QpackEncoderDynamicTable table = newDynamicTable(MAX_HEADER_TABLE_SIZE);
        addAndValidateHeader(table, emptyHeader);
    }

    @Test
    public void negativeCapacityIsDisallowed() {
        assertThrows(QpackException.class, () -> newDynamicTable(-1));
    }

    @Test
    public void capacityTooLarge() {
        assertThrows(QpackException.class, () -> newDynamicTable(Long.MAX_VALUE));
    }

    @Test
    public void delayAck() throws Exception {
        QpackEncoderDynamicTable table = newDynamicTable(16, 50, 128);

        addAndValidateHeader(table, emptyHeader);
        addAndValidateHeader(table, fooBarHeader);
        final int idx2 = addAndValidateHeader(table, fooBar2Header);

        assertThat(addHeader(table, fooBarHeader)).isLessThan(0);

        table.incrementKnownReceivedCount(3);
        assertThat(getEntryIndex(table, emptyHeader)).isLessThan(0);
        assertThat(getEntryIndex(table, fooBarHeader)).isLessThan(0);
        assertEquals(idx2, getEntryIndex(table, fooBar2Header));

        final int idx1 = addAndValidateHeader(table, emptyHeader);
        assertEquals(idx1, getEntryIndex(table, emptyHeader));
        assertThat(getEntryIndex(table, fooBar2Header)).isLessThan(0);
    }

    @Test
    public void addAndGet() throws Exception {
        QpackEncoderDynamicTable table = newDynamicTable(128);

        final int idx1 = addValidateAndAckHeader(table, emptyHeader);
        assertEquals(0, idx1);
        assertEquals(idx1, getEntryIndex(table, emptyHeader));

        final int idx2 = addValidateAndAckHeader(table, fooBarHeader);
        assertEquals(1, idx2);
        assertEquals(idx2, getEntryIndex(table, fooBarHeader));
        assertEquals(idx1, getEntryIndex(table, emptyHeader));
    }

    @Test
    public void nameOnlyMatch() throws Exception {
        final QpackEncoderDynamicTable table = newDynamicTable(128);
        addValidateAndAckHeader(table, fooBarHeader);
        final int lastIdx = addValidateAndAckHeader(table, fooBar2Header);

        final int idx = table.getEntryIndex("foo", "baz");
        assertThat(idx).isLessThan(0);
        assertEquals(-lastIdx - 1, idx);
    }

    @Test
    public void addDuplicateEntries() throws Exception {
        QpackEncoderDynamicTable table = newDynamicTable(128);

        final int idx1 = addValidateAndAckHeader(table, emptyHeader);
        assertEquals(idx1, getEntryIndex(table, emptyHeader));

        final int idx2 = addValidateAndAckHeader(table, fooBarHeader);
        assertEquals(idx2, getEntryIndex(table, fooBarHeader));

        final int idx3 = addValidateAndAckHeader(table, emptyHeader);
        // Return the most recent entry
        assertEquals(idx3, getEntryIndex(table, emptyHeader));
    }

    @Test
    public void hashCollisionThenRemove() throws Exception {
        // expected max size: 0.9*128 = 115
        QpackEncoderDynamicTable table = newDynamicTable(16, 10, 128);
        addValidateAndAckHeader(table, fooBarHeader); // size = 38
        addValidateAndAckHeader(table, fooBar2Header); // size = 77

        addValidateAndAckHeader(table, fooBar3Header); // size = 116, exceeds max threshold, should evict eldest

        assertThat(getEntryIndex(table, fooBarHeader)).isLessThan(0);
        assertThat(getEntryIndex(table, fooBar2Header)).isGreaterThanOrEqualTo(0);
        assertThat(getEntryIndex(table, fooBar3Header)).isGreaterThanOrEqualTo(0);
    }

    @Test
    public void requiredInsertCountWrapsAround() throws Exception {
        // maxIndex = 2 * maxEntries = 2 * 64/32 = 4
        QpackEncoderDynamicTable table = newDynamicTable(64);

        addValidateAndAckHeader(table, emptyHeader);
        addValidateAndAckHeader(table, emptyHeader);
        addValidateAndAckHeader(table, emptyHeader);
        addValidateAndAckHeader(table, emptyHeader);
        addValidateAndAckHeader(table, emptyHeader);
    }

    @Test
    public void indexWrapsAroundForSingleEntryCapacity() throws Exception {
        // maxIndex = 2 * maxEntries = 2 * 39/32 = 2
        QpackEncoderDynamicTable table = newDynamicTable(fooBar2Header.size());
        addValidateAndAckHeader(table, fooBar2Header);
        verifyTableEmpty(table);
        addValidateAndAckHeader(table, fooBar2Header);
    }

    @Test
    public void sectionAck() throws Exception {
        QpackEncoderDynamicTable table = newDynamicTable(128);

        final int idx = addAndValidateHeader(table, fooBarHeader);
        table.addReferenceToEntry(fooBarHeader.name, fooBarHeader.value, idx);
        table.acknowledgeInsertCountOnAck(idx);

        assertEquals(2, table.encodedKnownReceivedCount());
    }

    @Test
    public void sectionAckOutOfOrder() throws Exception {
        QpackEncoderDynamicTable table = newDynamicTable(128);

        final int idx1 = addAndValidateHeader(table, fooBarHeader);
        table.addReferenceToEntry(fooBarHeader.name, fooBarHeader.value, idx1);

        final int idx2 = addAndValidateHeader(table, fooBarHeader);
        table.addReferenceToEntry(fooBarHeader.name, fooBarHeader.value, idx2);

        table.acknowledgeInsertCountOnAck(idx2);
        assertEquals(3, table.encodedKnownReceivedCount());

        table.acknowledgeInsertCountOnAck(idx1);
        assertEquals(3, table.encodedKnownReceivedCount()); // already acked
    }

    @Test
    public void multipleReferences() throws Exception {
        // maxIndex = 2 * maxEntries = 2 * 39/32 = 2
        QpackEncoderDynamicTable table = newDynamicTable(fooBar3Header.size());

        final int idx1 = addAndValidateHeader(table, fooBar3Header);
        table.addReferenceToEntry(fooBar3Header.name, fooBar3Header.value, idx1);
        table.addReferenceToEntry(fooBar3Header.name, fooBar3Header.value, idx1);

        table.acknowledgeInsertCountOnAck(idx1);

        // first entry still active
        assertThat(addHeader(table, fooBar2Header)).isLessThan(0);

        table.acknowledgeInsertCountOnAck(idx1);
        verifyTableEmpty(table);
        addAndValidateHeader(table, fooBarHeader);
    }

    private void verifyTableEmpty(QpackEncoderDynamicTable table) {
        assertEquals(0, table.insertCount());
        insertCount = 0;
    }

    private int getEntryIndex(QpackEncoderDynamicTable table, QpackHeaderField emptyHeader) {
        return table.getEntryIndex(emptyHeader.name, emptyHeader.value);
    }

    private int addHeader(QpackEncoderDynamicTable table, QpackHeaderField header) {
        final int idx = table.add(header.name, header.value, header.size());
        if (idx >= 0) {
            insertCount++;
        }
        return idx;
    }

    private int addAndValidateHeader(QpackEncoderDynamicTable table, QpackHeaderField header) {
        final int addedIdx = addHeader(table, header);
        assertThat(addedIdx).isGreaterThanOrEqualTo(0);
        verifyInsertCount(table);
        return addedIdx;
    }

    private int addValidateAndAckHeader(QpackEncoderDynamicTable table, QpackHeaderField header) throws Exception {
        final int addedIdx = addAndValidateHeader(table, header);
        table.addReferenceToEntry(header.name, header.value, addedIdx);
        table.acknowledgeInsertCountOnAck(addedIdx);
        return addedIdx;
    }

    private QpackEncoderDynamicTable newDynamicTable(int arraySizeHint, int expectedFreeCapacityPercentage,
                                                     long maxCapacity) throws Exception {
        return setMaxTableCapacity(maxCapacity,
                new QpackEncoderDynamicTable(arraySizeHint, expectedFreeCapacityPercentage));
    }

    private QpackEncoderDynamicTable newDynamicTable(long maxCapacity) throws Exception {
        return setMaxTableCapacity(maxCapacity, new QpackEncoderDynamicTable());
    }

    private QpackEncoderDynamicTable setMaxTableCapacity(long maxCapacity, QpackEncoderDynamicTable table)
            throws Exception {
        table.maxTableCapacity(maxCapacity);
        this.maxCapacity = maxCapacity;
        return table;
    }

    private void verifyInsertCount(QpackEncoderDynamicTable table) {
        assertEquals(expectedInsertCount(), table.encodedRequiredInsertCount(table.insertCount()),
                "Unexpected required insert count.");
    }

    private int expectedInsertCount() {
        return insertCount == 0 ? 0 : toIntExact((insertCount % (2 * Math.floorDiv(maxCapacity, 32))) + 1);
    }
}
