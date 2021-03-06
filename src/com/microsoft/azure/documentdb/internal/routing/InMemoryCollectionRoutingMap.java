package com.microsoft.azure.documentdb.internal.routing;

import java.util.*;

import org.apache.commons.lang3.tuple.ImmutablePair;

import com.microsoft.azure.documentdb.PartitionKeyRange;

public class InMemoryCollectionRoutingMap<TPartitionInfo> implements CollectionRoutingMap {
    private final Map<String, ImmutablePair<PartitionKeyRange, TPartitionInfo>> rangeById;
    private final Map<TPartitionInfo, PartitionKeyRange> rangeByInfo;
    private final List<PartitionKeyRange> orderedPartitionKeyRanges;
    private final List<Range<String>> orderedRanges;
    private final List<TPartitionInfo> orderedPartitionInfo;
    private String collectionUniqueId;

    private InMemoryCollectionRoutingMap(Map<String, ImmutablePair<PartitionKeyRange, TPartitionInfo>> rangeById,
            Map<TPartitionInfo, PartitionKeyRange> rangeByInfo, List<PartitionKeyRange> orderedPartitionKeyRanges,
            List<TPartitionInfo> orderedPartitionInfo, String collectionUniqueId) {
        this.rangeById = rangeById;
        this.rangeByInfo = rangeByInfo;
        this.orderedPartitionKeyRanges = orderedPartitionKeyRanges;
        this.orderedRanges = new ArrayList<Range<String>>(this.orderedPartitionKeyRanges.size());
        for (PartitionKeyRange range : this.orderedPartitionKeyRanges) {
            this.orderedRanges.add(range.toRange());
        }

        this.orderedPartitionInfo = orderedPartitionInfo;
        this.collectionUniqueId = collectionUniqueId;
    }

    public static <TPartitionInfo> InMemoryCollectionRoutingMap<TPartitionInfo> tryCreateCompleteRoutingMap(
            Iterable<ImmutablePair<PartitionKeyRange, TPartitionInfo>> ranges, String collectionUniqueId) {
        Map<String, ImmutablePair<PartitionKeyRange, TPartitionInfo>> rangeById = new HashMap<String, ImmutablePair<PartitionKeyRange, TPartitionInfo>>();
        Map<TPartitionInfo, PartitionKeyRange> rangeByInfo = new HashMap<TPartitionInfo, PartitionKeyRange>();

        List<ImmutablePair<PartitionKeyRange, TPartitionInfo>> sortedRanges = new ArrayList<ImmutablePair<PartitionKeyRange, TPartitionInfo>>();
        for (ImmutablePair<PartitionKeyRange, TPartitionInfo> pair : ranges) {
            rangeById.put(pair.left.getId(), pair);
            rangeByInfo.put(pair.right, pair.left);
            sortedRanges.add(pair);
        }

        Collections.sort(sortedRanges, new MinPartitionKeyPairComparator<TPartitionInfo>());

        List<PartitionKeyRange> orderedPartitionKeyRanges = new ArrayList<PartitionKeyRange>(sortedRanges.size());
        List<TPartitionInfo> orderedPartitionInfo = new ArrayList<TPartitionInfo>(sortedRanges.size());

        for (ImmutablePair<PartitionKeyRange, TPartitionInfo> pair : sortedRanges) {
            orderedPartitionKeyRanges.add(pair.left);
            orderedPartitionInfo.add(pair.right);
        }

        if (!isCompleteSetOfRanges(orderedPartitionKeyRanges)) {
            return null;
        }

        return new InMemoryCollectionRoutingMap<TPartitionInfo>(rangeById, rangeByInfo, orderedPartitionKeyRanges,
                orderedPartitionInfo, collectionUniqueId);
    }

    private static boolean isCompleteSetOfRanges(List<PartitionKeyRange> orderedRanges) {
        boolean isComplete = false;
        if (orderedRanges.size() > 0) {
            PartitionKeyRange firstRange = orderedRanges.get(0);
            PartitionKeyRange lastRange = orderedRanges.get(orderedRanges.size() - 1);
            isComplete = firstRange.getMinInclusive()
                    .compareTo(PartitionKeyRange.MINIMUM_INCLUSIVE_EFFECTIVE_PARTITION_KEY) == 0;
            isComplete &= lastRange.getMaxExclusive()
                    .compareTo(PartitionKeyRange.MAXIMUM_EXCLUSIVE_EFFECTIVE_PARTITION_KEY) == 0;

            for (int i = 1; i < orderedRanges.size(); i++) {
                PartitionKeyRange previousRange = orderedRanges.get(i - 1);
                PartitionKeyRange currentRange = orderedRanges.get(i);
                isComplete &= previousRange.getMaxExclusive().compareTo(currentRange.getMinInclusive()) == 0;

                if (!isComplete) {
                    if (previousRange.getMaxExclusive().compareTo(currentRange.getMinInclusive()) > 0) {
                        throw new IllegalStateException("Ranges overlap");
                    }

                    break;
                }
            }
        }

        return isComplete;
    }

    public String getCollectionUniqueId() {
        return collectionUniqueId;
    }

    public TPartitionInfo getHeadPartition() {
        return this.orderedPartitionInfo.get(0);
    }

    public TPartitionInfo getTailPartition() {
        return this.orderedPartitionInfo.get(this.orderedPartitionInfo.size() - 1);
    }

    public List<TPartitionInfo> getOrderedPartitionInfo() {
        return this.orderedPartitionInfo;
    }

    @Override
    public List<PartitionKeyRange> getOrderedPartitionKeyRanges() {
        return this.orderedPartitionKeyRanges;
    }

    @Override
    public PartitionKeyRange getRangeByEffectivePartitionKey(String effectivePartitionKeyValue) {
        if (PartitionKeyRange.MINIMUM_INCLUSIVE_EFFECTIVE_PARTITION_KEY.compareTo(effectivePartitionKeyValue) == 0) {
            return this.orderedPartitionKeyRanges.get(0);
        }

        if (PartitionKeyRange.MAXIMUM_EXCLUSIVE_EFFECTIVE_PARTITION_KEY.compareTo(effectivePartitionKeyValue) == 0) {
            return null;
        }

        int index = Collections.binarySearch(this.orderedRanges, Range.getPointRange(effectivePartitionKeyValue),
                new Range.MinComparator<String>());

        if (index < 0) {
            index = Math.max(0, -index - 2);
        }

        return this.orderedPartitionKeyRanges.get(index);
    }

    @Override
    public PartitionKeyRange getRangeByPartitionKeyRangeId(String partitionKeyRangeId) {
        ImmutablePair<PartitionKeyRange, TPartitionInfo> pair = this.rangeById.get(partitionKeyRangeId);
        return pair == null ? null : pair.left;
    }

    public TPartitionInfo getInfoByPartitionKeyRangeId(String partitionKeyRangeId) {
        ImmutablePair<PartitionKeyRange, TPartitionInfo> pair = this.rangeById.get(partitionKeyRangeId);
        return pair == null ? null : pair.right;
    }

    public PartitionKeyRange getPartitionKeyRangeByPartitionInfo(TPartitionInfo partitionInfo) {
        return this.rangeByInfo.get(partitionInfo);
    }

    @Override
    public Collection<PartitionKeyRange> getOverlappingRanges(Range<String> range) {
        return this.getOverlappingRanges(Arrays.asList(range));
    }

    @Override
    public Collection<PartitionKeyRange> getOverlappingRanges(Collection<Range<String>> providedPartitionKeyRanges) {
        if (providedPartitionKeyRanges == null) {
            throw new IllegalArgumentException("providedPartitionKeyRanges");
        }

        Map<String, PartitionKeyRange> partitionRanges = new TreeMap<String, PartitionKeyRange>();

        for (Range<String> range : providedPartitionKeyRanges) {
            int minIndex = Collections.binarySearch(this.orderedRanges, range, new Range.MinComparator<String>());
            if (minIndex < 0) {
                minIndex = Math.max(minIndex, -minIndex - 2);
            }

            int maxIndex = Collections.binarySearch(this.orderedRanges, range, new Range.MaxComparator<String>());
            if (maxIndex < 0) {
                maxIndex = Math.min(this.orderedRanges.size() - 1, -maxIndex - 1);
            }

            for (int i = minIndex; i <= maxIndex; ++i) {
                if (Range.checkOverlapping(this.orderedRanges.get(i), range)) {
                    PartitionKeyRange partitionKeyRange = this.orderedPartitionKeyRanges.get(i);
                    partitionRanges.put(partitionKeyRange.getMinInclusive(), partitionKeyRange);
                }
            }
        }

        return partitionRanges.values();
    }

    private static class MinPartitionKeyPairComparator<TPartitionInfo>
            implements Comparator<ImmutablePair<PartitionKeyRange, TPartitionInfo>> {
        public int compare(ImmutablePair<PartitionKeyRange, TPartitionInfo> pair1,
                ImmutablePair<PartitionKeyRange, TPartitionInfo> pair2) {
            return pair1.left.getMinInclusive().compareTo(pair2.left.getMinInclusive());
        }
    }
}
