/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.mediadriver;

import uk.co.real_logic.aeron.mediadriver.buffer.BufferRotator;
import uk.co.real_logic.aeron.util.BufferRotationDescriptor;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.logbuffer.LogBuffer;
import uk.co.real_logic.aeron.util.concurrent.logbuffer.LogRebuilder;
import uk.co.real_logic.aeron.util.event.EventLogger;
import uk.co.real_logic.aeron.util.protocol.DataHeaderFlyweight;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import static uk.co.real_logic.aeron.util.BufferRotationDescriptor.*;
import static uk.co.real_logic.aeron.util.concurrent.logbuffer.LogBufferDescriptor.*;

/**
 * State maintained for active sessionIds within a channel for receiver processing
 */
public class DriverConnectedSubscription
{
    private static final EventLogger LOGGER = new EventLogger(DriverConnectedSubscription.class);

    /**
     * Handler for sending Status Messages (SMs)
     */
    @FunctionalInterface
    public interface SendSmHandler
    {
        /**
         * Called when an SM should be sent.
         *
         * @param termId     for the SM
         * @param termOffset for the SM
         * @param window     for the SM
         */
        void sendSm(final long termId, final int termOffset, final int window);
    }

    /** Timeout between SMs. One RTT. */
    public static final long STATUS_MESSAGE_TIMEOUT = MediaDriver.ESTIMATED_RTT_NS;

    private final String destination;
    private final InetSocketAddress srcAddress;
    private final long sessionId;
    private final long channelId;

    private final AtomicLong cleanedTermId = new AtomicLong(UNKNOWN_TERM_ID);
    private final AtomicLong currentTermId = new AtomicLong(UNKNOWN_TERM_ID);
    private int currentBufferIndex = 0;

    private BufferRotator rotator;
    private LogRebuilder[] rebuilders;
    private LossHandler lossHandler;

    private SendSmHandler sendSmHandler;
    private long lastSmTimestamp;
    private long lastSmTermId;
    private int lastSmTail;
    private int currentWindow;
    private int currentWindowGain;

    public DriverConnectedSubscription(final String destination,
                                       final long sessionId,
                                       final long channelId,
                                       final InetSocketAddress srcAddress)
    {
        this.destination = destination;
        this.srcAddress = srcAddress;
        this.sessionId = sessionId;
        this.channelId = channelId;
    }

    public void termBuffer(final long initialTermId,
                           final int initialWindow,
                           final BufferRotator rotator,
                           final LossHandler lossHandler,
                           final SendSmHandler sendSmHandler)
    {
        cleanedTermId.lazySet(initialTermId + CLEAN_WINDOW);
        currentTermId.lazySet(initialTermId);
        this.rotator = rotator;
        rebuilders = rotator.buffers()
                            .map((rawLog) -> new LogRebuilder(rawLog.logBuffer(), rawLog.stateBuffer()))
                            .toArray(LogRebuilder[]::new);
        this.lossHandler = lossHandler;
        this.sendSmHandler = sendSmHandler;

        // attaching this term buffer will send an SM, so save the params sent for comparison
        this.lastSmTermId = initialTermId;
        this.lastSmTail = lossHandler.highestContiguousOffset();
        this.currentWindow = initialWindow;
        this.currentWindowGain = currentWindow << 2; // window / 4
    }

    public InetSocketAddress sourceAddress()
    {
        return srcAddress;
    }

    public long sessionId()
    {
        return sessionId;
    }

    public long channelId()
    {
        return channelId;
    }

    public void rebuildBuffer(final DataHeaderFlyweight header, final AtomicBuffer buffer, final long length)
    {
        final long termId = header.termId();
        final long currentTermId = this.currentTermId.get();

        // TODO: handle packets outside acceptable range - can be done with position tracking

        if (termId == currentTermId)
        {
            rebuilders[currentBufferIndex].insert(buffer, 0, (int)length);
        }
        else if (termId == (currentTermId + 1))
        {
            nextTerm(termId, currentTermId);
            rebuilders[currentBufferIndex].insert(buffer, 0, (int)length);
        }
    }

    private void nextTerm(final long termId, final long currentTermId)
    {
        final int nextIndex = BufferRotationDescriptor.rotateNext(currentBufferIndex);
        final LogRebuilder rebuilder = rebuilders[nextIndex];

        if (CLEAN != rebuilder.status())
        {
            System.err.println(String.format("Term not clean: destination=%s channelId=%d, required termId=%d",
                                             destination, channelId, currentTermId + 1));

            if (rebuilder.compareAndSetStatus(NEEDS_CLEANING, IN_CLEANING))
            {
                rebuilder.clean(); // Conductor is not keeping up so do it yourself!!!
            }
            else
            {
                while (CLEAN != rebuilder.status())
                {
                    Thread.yield();
                }
            }
        }

        final int previousIndex = rotatePrevious(currentBufferIndex);
        rebuilders[previousIndex].statusOrdered(NEEDS_CLEANING);

        this.currentTermId.lazySet(termId);
        currentBufferIndex = nextIndex;
    }

    /**
     * Called from the MediaConductor.
     *
     * @return if work has been done or not
     */
    public int cleanLogBuffer()
    {

        if (rebuilders != null) {
            for (final LogBuffer logBuffer : rebuilders)
            {
                if (logBuffer.status() == NEEDS_CLEANING && logBuffer.compareAndSetStatus(NEEDS_CLEANING, IN_CLEANING))
                {
                    logBuffer.clean();

                    return 1;
                }
            }
        }

        return 0;
    }

    /**
     * Called from the MediaConductor.
     *
     * @return if work has been done or not
     */
    public int scanForGaps()
    {
        if (null != lossHandler)
        {
            lossHandler.scan();
        }

        // scan lazily
        return 0;
    }

    /**
     * Called form the MediaConductor.
     *
     * @return number of work items processed.
     */
    public int sendAnyPendingSm(final long currentTimestamp)
    {
        if (null == lossHandler || null == sendSmHandler)
        {
            return 0;
        }

        /*
         * General approach is to check tail and see if it has moved enough to warrant sending an SM.
         * - send SM when termId has moved (i.e. buffer rotation of LossHandler - i.e. term completed)
         * - send SM when (currentTail - lastSmTail) > X% of window (the window gain)
         * - send SM when currentTail > lastTail && timeOfLastSM too long
         */

        final int currentSmTail = lossHandler.highestContiguousOffset();
        final long currentSmTermId = lossHandler.currentTermId();

        // if term has rotated for loss handler, then send an SM
        if (lossHandler.currentTermId() != lastSmTermId)
        {
            lastSmTimestamp = currentTimestamp;
            return sendSm(currentSmTermId, currentSmTail, currentWindow);
        }

        // made progress since last time we sent an SM, so may send
        if (currentSmTail > lastSmTail)
        {
            // see if we have made enough progress to make sense to send an SM
            if ((currentSmTail - lastSmTail) > currentWindowGain)
            {
                lastSmTimestamp = currentTimestamp;
                return sendSm(currentSmTermId, currentSmTail, currentWindow);
            }

            // lastSmTimestamp might be 0 due to being initialized, but if we have sent some, then fine.
//            if (currentTimestamp > (lastSmTimestamp + STATUS_MESSAGE_TIMEOUT) && lastSmTimestamp > 0)
//            {
//                lastSmTimestamp = currentTimestamp;
//                return sendSm(currentSmTermId, currentSmTail, currentWindow);
//            }
        }

        // invert the work count logic. We want to appear to be less busy once we send an SM
        return 1;
    }

    private int sendSm(final long termId, final int termOffset, final int window)
    {
        sendSmHandler.sendSm(termId, termOffset, window);
        lastSmTermId = termId;
        lastSmTail = termOffset;

        return 0;
    }
}
