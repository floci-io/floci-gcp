package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.InstanceSplit;
import com.google.cloud.run.v2.InstanceSplitAllocationType;
import com.google.cloud.run.v2.InstanceSplitStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudRunWorkerPoolsServiceTest {

    private static final Pattern REVISION_ID = Pattern.compile("floci-wp-\\d{5}-[a-z0-9]{3}");

    @Test
    void firstRevisionIdStartsCounterAtOneWithThreeCharacterSuffix() {
        String id = CloudRunWorkerPoolsService.nextRevisionId("floci-wp", null, new Random(1));

        assertTrue(REVISION_ID.matcher(id).matches(), id);
        assertTrue(id.startsWith("floci-wp-00001-"), id);
    }

    @Test
    void nextRevisionIdCountsOnFromFullOrShortPreviousName() {
        String fromFull = CloudRunWorkerPoolsService.nextRevisionId("floci-wp",
                "projects/p/locations/l/workerPools/floci-wp/revisions/floci-wp-00002-jw2", new Random(2));
        String fromShort = CloudRunWorkerPoolsService.nextRevisionId("floci-wp", "floci-wp-00041-abc",
                new Random(3));

        assertTrue(fromFull.startsWith("floci-wp-00003-"), fromFull);
        assertTrue(fromShort.startsWith("floci-wp-00042-"), fromShort);
    }

    @Test
    void revisionCounterIgnoresNamesOfOtherPools() {
        assertEquals(0, CloudRunWorkerPoolsService.revisionCounter("wp", "other-00007-abc"));
        assertEquals(0, CloudRunWorkerPoolsService.revisionCounter("wp", "wp-abcde-xyz"));
        assertEquals(7, CloudRunWorkerPoolsService.revisionCounter("wp", "wp-00007-xyz"));
    }

    @Test
    void revisionSuffixUsesOnlyLowercaseAlphanumerics() {
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            String id = CloudRunWorkerPoolsService.nextRevisionId("floci-wp", null, random);
            assertTrue(REVISION_ID.matcher(id).matches(), id);
        }
    }

    @Test
    void latestSplitStatusNamesTheConcreteRevision() {
        List<InstanceSplitStatus> statuses = CloudRunWorkerPoolsService.splitStatuses(List.of(
                InstanceSplit.newBuilder()
                        .setType(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST)
                        .setPercent(100)
                        .build()), "floci-wp-00003-jlb");

        assertEquals(1, statuses.size());
        assertEquals(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST, statuses.get(0).getType());
        assertEquals("floci-wp-00003-jlb", statuses.get(0).getRevision());
        assertEquals(100, statuses.get(0).getPercent());
    }

    @Test
    void revisionSplitStatusUsesShortRevisionNameAndServingRevisionHasLargestPercent() {
        List<InstanceSplitStatus> statuses = CloudRunWorkerPoolsService.splitStatuses(List.of(
                InstanceSplit.newBuilder()
                        .setType(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION)
                        .setRevision("projects/p/locations/l/workerPools/wp/revisions/wp-00001-bdg")
                        .setPercent(30)
                        .build(),
                InstanceSplit.newBuilder()
                        .setType(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST)
                        .setPercent(70)
                        .build()), "wp-00002-jw2");

        assertEquals("wp-00001-bdg", statuses.get(0).getRevision());
        assertEquals(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION, statuses.get(0).getType());
        assertEquals("wp-00002-jw2", statuses.get(1).getRevision());
        assertEquals("wp-00002-jw2", CloudRunWorkerPoolsService.servingRevision(statuses));
        assertEquals(2, CloudRunWorkerPoolsService.servingRevisionIds(statuses).size());
    }

    @Test
    void servingRevisionPrefersFirstOnTiesAndIsNullWithoutInstances() {
        List<InstanceSplitStatus> tied = List.of(
                InstanceSplitStatus.newBuilder().setRevision("wp-00001-aaa").setPercent(50).build(),
                InstanceSplitStatus.newBuilder().setRevision("wp-00002-bbb").setPercent(50).build());

        assertEquals("wp-00001-aaa", CloudRunWorkerPoolsService.servingRevision(tied));
        assertNull(CloudRunWorkerPoolsService.servingRevision(List.of()));
    }

    @Test
    void untypedSplitIsInferredFromRevisionPresence() {
        List<InstanceSplitStatus> statuses = CloudRunWorkerPoolsService.splitStatuses(List.of(
                InstanceSplit.newBuilder().setPercent(100).build()), "wp-00004-abc");

        assertEquals(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST, statuses.get(0).getType());
        assertEquals("wp-00004-abc", statuses.get(0).getRevision());
    }

    @Test
    void imageBaseNameStripsRegistryTagAndDigest() {
        assertEquals("busybox", CloudRunWorkerPoolsService.imageBaseName("docker.io/library/busybox"));
        assertEquals("busybox", CloudRunWorkerPoolsService.imageBaseName("busybox:1.36"));
        assertEquals("app", CloudRunWorkerPoolsService.imageBaseName("localhost:5000/team/app:v1"));
        assertEquals("busybox", CloudRunWorkerPoolsService.imageBaseName(
                "mirror.gcr.io/library/busybox@sha256:f97baa533a26513c453a362be331a43eb60214302f449c16571b23cea141dce4"));
    }
}
