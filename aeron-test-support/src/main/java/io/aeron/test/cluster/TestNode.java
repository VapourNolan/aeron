/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.test.cluster;

import io.aeron.Counter;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.archive.Archive;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.status.RecordingPos;
import io.aeron.cluster.*;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.status.SystemCounterDescriptor;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.test.DataCollector;
import io.aeron.test.driver.TestMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.aeron.Aeron.NULL_VALUE;
import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.junit.jupiter.api.Assertions.fail;

public class TestNode implements AutoCloseable
{
    private final ClusteredArchive clusteredArchive;
    private final ClusteredServiceContainer container;
    private final TestService service;
    private final Context context;
    private final TestMediaDriver mediaDriver;
    private boolean isClosed = false;

    TestNode(final Context context, final DataCollector dataCollector)
    {
        mediaDriver = TestMediaDriver.launch(context.mediaDriverContext, null);
        clusteredArchive = ClusteredArchive.launch(
            mediaDriver.aeronDirectoryName(),
            context.archiveContext,
            context.consensusModuleContext.terminationHook(ClusterTests.dynamicTerminationHook(
                context.terminationExpected, context.memberWasTerminated)));

        container = ClusteredServiceContainer.launch(
            context.serviceContainerContext
                .terminationHook(ClusterTests.dynamicTerminationHook(
                    context.terminationExpected, context.serviceWasTerminated)));

        service = context.service;
        this.context = context;

        dataCollector.add(container.context().clusterDir().toPath());
        dataCollector.add(clusteredArchive.consensusModule().context().clusterDir().toPath());
        dataCollector.add(clusteredArchive.archive().context().archiveDir().toPath());
        dataCollector.add(mediaDriver.context().aeronDirectory().toPath());
    }

    public ConsensusModule consensusModule()
    {
        return clusteredArchive.consensusModule();
    }

    public ClusteredServiceContainer container()
    {
        return container;
    }

    public TestService service()
    {
        return service;
    }

    public void close()
    {
        if (!isClosed)
        {
            isClosed = true;
            CloseHelper.closeAll(clusteredArchive.consensusModule(), container, clusteredArchive, mediaDriver);
        }
    }

    void closeAndDelete()
    {
        Throwable error = null;

        try
        {
            if (!isClosed)
            {
                close();
            }
        }
        catch (final Throwable t)
        {
            error = t;
        }

        try
        {
            if (null != container)
            {
                container.context().deleteDirectory();
            }
        }
        catch (final Throwable t)
        {
            if (error == null)
            {
                error = t;
            }
            else
            {
                error.addSuppressed(t);
            }
        }

        try
        {
            if (null != clusteredArchive)
            {
                clusteredArchive.consensusModule().context().deleteDirectory();
                clusteredArchive.archive().context().deleteDirectory();
                mediaDriver.context().deleteDirectory();
            }
        }
        catch (final Throwable t)
        {
            if (error == null)
            {
                error = t;
            }
            else
            {
                error.addSuppressed(t);
            }
        }

        if (null != error)
        {
            LangUtil.rethrowUnchecked(error);
        }
    }

    boolean isClosed()
    {
        return isClosed;
    }

    public Cluster.Role role()
    {
        final ConsensusModule.Context context = clusteredArchive.consensusModule().context();
        if (context.aeron().isClosed())
        {
            return Cluster.Role.FOLLOWER;
        }

        return Cluster.Role.get(context.clusterNodeRoleCounter());
    }

    ElectionState electionState()
    {
        final ConsensusModule.Context context = clusteredArchive.consensusModule().context();
        if (context.aeron().isClosed())
        {
            return ElectionState.CLOSED;
        }

        return ElectionState.get(context.electionStateCounter());
    }

    ConsensusModule.State moduleState()
    {
        final ConsensusModule.Context context = clusteredArchive.consensusModule().context();
        if (context.aeron().isClosed())
        {
            return ConsensusModule.State.CLOSED;
        }

        return ConsensusModule.State.get(context.moduleStateCounter());
    }

    public long commitPosition()
    {
        final ConsensusModule.Context context = clusteredArchive.consensusModule().context();
        final Counter counter = context.commitPositionCounter();
        if (counter.isClosed() || context.aeron().isClosed())
        {
            return NULL_POSITION;
        }

        return counter.get();
    }

    public long appendPosition()
    {
        final long recordingId = consensusModule().context().recordingLog().findLastTermRecordingId();
        if (RecordingPos.NULL_RECORDING_ID == recordingId)
        {
            fail("no recording for last term");
        }

        final CountersReader countersReader = countersReader();
        final int counterId = RecordingPos.findCounterIdByRecording(countersReader, recordingId);
        if (NULL_VALUE == counterId)
        {
            fail("recording not active " + recordingId);
        }

        return countersReader.getCounterValue(counterId);
    }

    boolean isLeader()
    {
        return role() == Cluster.Role.LEADER && moduleState() != ConsensusModule.State.CLOSED;
    }

    boolean isFollower()
    {
        return role() == Cluster.Role.FOLLOWER;
    }

    public void terminationExpected(final boolean terminationExpected)
    {
        context.terminationExpected.set(terminationExpected);
    }

    boolean hasServiceTerminated()
    {
        return context.serviceWasTerminated.get();
    }

    public boolean hasMemberTerminated()
    {
        return context.memberWasTerminated.get();
    }

    public int index()
    {
        return service.index();
    }

    CountersReader countersReader()
    {
        return mediaDriver.counters();
    }

    public long errors()
    {
        return countersReader().getCounterValue(SystemCounterDescriptor.ERRORS.id());
    }

    public ClusterMembership clusterMembership()
    {
        final ClusterMembership clusterMembership = new ClusterMembership();
        final File clusterDir = clusteredArchive.consensusModule().context().clusterDir();

        if (!ClusterTool.listMembers(clusterMembership, clusterDir, TimeUnit.SECONDS.toMillis(3)))
        {
            throw new IllegalStateException("timeout waiting for cluster members info");
        }

        return clusterMembership;
    }

    public void removeMember(final int followerMemberId, final boolean isPassive)
    {
        final File clusterDir = clusteredArchive.consensusModule().context().clusterDir();

        if (!ClusterTool.removeMember(clusterDir, followerMemberId, isPassive))
        {
            throw new IllegalStateException("could not remove member");
        }
    }

    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    public static class TestService extends StubClusteredService
    {
        static final int SNAPSHOT_FRAGMENT_COUNT = 500;
        static final int SNAPSHOT_MSG_LENGTH = 1000;

        private int index;
        private volatile int activeSessionCount;
        private volatile int messageCount;
        private volatile boolean wasSnapshotTaken = false;
        private volatile boolean wasSnapshotLoaded = false;
        private volatile boolean hasReceivedUnexpectedMessage = false;
        private volatile Cluster.Role roleChangedTo = null;

        TestService index(final int index)
        {
            this.index = index;
            return this;
        }

        int index()
        {
            return index;
        }

        int activeSessionCount()
        {
            return activeSessionCount;
        }

        public int messageCount()
        {
            return messageCount;
        }

        public boolean wasSnapshotTaken()
        {
            return wasSnapshotTaken;
        }

        public void resetSnapshotTaken()
        {
            wasSnapshotTaken = false;
        }

        public boolean wasSnapshotLoaded()
        {
            return wasSnapshotLoaded;
        }

        public Cluster.Role roleChangedTo()
        {
            return roleChangedTo;
        }

        public Cluster cluster()
        {
            return cluster;
        }

        boolean hasReceivedUnexpectedMessage()
        {
            return hasReceivedUnexpectedMessage;
        }

        public void onStart(final Cluster cluster, final Image snapshotImage)
        {
            super.onStart(cluster, snapshotImage);

            if (null != snapshotImage)
            {
                activeSessionCount = cluster.clientSessions().size();

                final FragmentHandler handler =
                    (buffer, offset, length, header) -> messageCount = buffer.getInt(offset);

                int fragmentCount = 0;
                while (true)
                {
                    final int fragments = snapshotImage.poll(handler, 10);
                    fragmentCount += fragments;

                    if (snapshotImage.isClosed() || snapshotImage.isEndOfStream())
                    {
                        break;
                    }

                    idleStrategy.idle(fragments);
                }

                if (fragmentCount != SNAPSHOT_FRAGMENT_COUNT)
                {
                    throw new AgentTerminationException(
                        "unexpected snapshot length: expected=" + SNAPSHOT_FRAGMENT_COUNT + " actual=" + fragmentCount);
                }

                wasSnapshotLoaded = true;
            }
        }

        public void onSessionMessage(
            final ClientSession session,
            final long timestamp,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header)
        {
            final String message = buffer.getStringWithoutLengthAscii(offset, length);
            if (message.equals(ClusterTests.REGISTER_TIMER_MSG))
            {
                while (!cluster.scheduleTimer(1, cluster.time() + 1_000))
                {
                    idleStrategy.idle();
                }
            }

            if (message.equals(ClusterTests.UNEXPECTED_MSG))
            {
                hasReceivedUnexpectedMessage = true;
                throw new IllegalStateException("unexpected message received");
            }

            if (message.equals(ClusterTests.ECHO_IPC_INGRESS_MSG))
            {
                if (null != session)
                {
                    while (cluster.offer(buffer, offset, length) < 0)
                    {
                        idleStrategy.idle();
                    }
                }
                else
                {
                    for (final ClientSession clientSession : cluster.clientSessions())
                    {
                        while (clientSession.offer(buffer, offset, length) < 0)
                        {
                            idleStrategy.idle();
                        }
                    }
                }
            }
            else
            {
                if (null != session)
                {
                    while (session.offer(buffer, offset, length) < 0)
                    {
                        idleStrategy.idle();
                    }
                }
            }

            ++messageCount;
        }

        public void onTakeSnapshot(final ExclusivePublication snapshotPublication)
        {
            final UnsafeBuffer buffer = new UnsafeBuffer(new byte[SNAPSHOT_MSG_LENGTH]);
            buffer.putInt(0, messageCount);
            buffer.putInt(SNAPSHOT_MSG_LENGTH - SIZE_OF_INT, messageCount);

            for (int i = 0; i < SNAPSHOT_FRAGMENT_COUNT; i++)
            {
                idleStrategy.reset();
                while (snapshotPublication.offer(buffer, 0, SNAPSHOT_MSG_LENGTH) <= 0)
                {
                    idleStrategy.idle();
                }
            }

            wasSnapshotTaken = true;
        }

        public void onSessionOpen(final ClientSession session, final long timestamp)
        {
            super.onSessionOpen(session, timestamp);
            activeSessionCount += 1;
        }

        public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason closeReason)
        {
            super.onSessionClose(session, timestamp, closeReason);
            activeSessionCount -= 1;
        }

        public void onRoleChange(final Cluster.Role newRole)
        {
            roleChangedTo = newRole;
        }
    }

    static class Context
    {
        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context();
        final Archive.Context archiveContext = new Archive.Context();
        final AeronArchive.Context aeronArchiveContext = new AeronArchive.Context();
        final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context();
        final ClusteredServiceContainer.Context serviceContainerContext = new ClusteredServiceContainer.Context();
        final AtomicBoolean terminationExpected = new AtomicBoolean(false);
        final AtomicBoolean memberWasTerminated = new AtomicBoolean(false);
        final AtomicBoolean serviceWasTerminated = new AtomicBoolean(false);
        final TestService service;

        Context(final TestService service)
        {
            this.service = service;
        }
    }
}
