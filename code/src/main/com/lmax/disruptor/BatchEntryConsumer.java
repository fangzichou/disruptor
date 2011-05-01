package com.lmax.disruptor;

/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available {@link Entry}s to a {@link BatchEntryHandler}.
 *
 * @param <T> Entry implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEntryConsumer<T extends Entry>
    implements EntryConsumer
{
    private volatile boolean running = true;
    private volatile long sequence = -1L;

    private final ThresholdBarrier<T> barrier;
    private final BatchEntryHandler<T> entryHandler;
    private final boolean noProgressTracker;
    private ExceptionHandler exceptionHandler = new FatalExceptionHandler();


    /**
     * Construct a batch consumer that will automatically track the progress by updating its sequence when
     * the onAvailable method returns from the delegated call to the {@link BatchEntryHandler}
     *
     * @param barrier on which it is waiting.
     * @param entryHandler is the delegate to which {@link Entry}s are dispatched.
     */
    public BatchEntryConsumer(final ThresholdBarrier<T> barrier,
                              final BatchEntryHandler<T> entryHandler)
    {
        this.barrier = barrier;
        this.entryHandler = entryHandler;
        this.noProgressTracker = true;
    }

    /**
     * Construct a batch consumer that will rely on the {@link ProgressReportingEntryHandler}
     * to callback via the {@link BatchEntryConsumer.ProgressTrackerCallback} when it has completed with a sequence.
     *
     * @param barrier on which it is waiting.
     * @param entryHandler is the delegate to which {@link Entry}s are dispatched.
     */
    public BatchEntryConsumer(final ThresholdBarrier<T> barrier,
                              final ProgressReportingEntryHandler<T> entryHandler)
    {
        this.barrier = barrier;
        this.entryHandler = entryHandler;

        this.noProgressTracker = false;
        entryHandler.setProgressTracker(new ProgressTrackerCallback());
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEntryConsumer}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public long getSequence()
    {
        return sequence;
    }

    @Override
    public ThresholdBarrier<? extends T> getBarrier()
    {
        return barrier;
    }

    @Override
    public void halt()
    {
        running = false;
    }

    @Override
    public void run()
    {
        T entry = null;

        while (running)
        {
            try
            {
                final long nextSequence = sequence + 1;
                final long availableSeq = barrier.waitFor(nextSequence);

                for (long i = nextSequence; i <= availableSeq; i++)
                {
                    entry = barrier.getRingBuffer().getEntry(i);
                    entryHandler.onAvailable(entry);

                    if (noProgressTracker)
                    {
                        sequence = i;
                    }
                }

                entryHandler.onEndOfBatch();
            }
            catch (final AlertException ex)
            {
                // Wake up from blocking wait and check if we should continue to run
            }
            catch (final Exception ex)
            {
                exceptionHandler.handle(ex, entry);
                if (noProgressTracker)
                {
                    sequence++;
                }
            }
        }

        entryHandler.onCompletion();
    }

    /**
     * Used by the {@link BatchEntryConsumer} to signal when it has completed consuming a given sequence.
     */
    public final class ProgressTrackerCallback
    {
        /**
         * Signal that the sequence has been consumed.
         *
         * @param sequence that has been consumed.
         */
        public void onCompleted(final long sequence)
        {
            BatchEntryConsumer.this.sequence = sequence;
        }
    }
}