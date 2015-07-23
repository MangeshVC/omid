package com.yahoo.omid.committable;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.yahoo.omid.committable.CommitTable.CommitTimestamp.Location;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCommitTable implements CommitTable {
    final ConcurrentHashMap<Long, Long> table = new ConcurrentHashMap<Long, Long>();

    long lowWatermark;

    @Override
    public ListenableFuture<CommitTable.Writer> getWriter() {
        SettableFuture<CommitTable.Writer> f = SettableFuture.<CommitTable.Writer>create();
        f.set(new Writer());
        return f;
    }

    @Override
    public ListenableFuture<CommitTable.Client> getClient() {
        SettableFuture<CommitTable.Client> f = SettableFuture.<CommitTable.Client>create();
        f.set(new Client());
        return f;
    }

    public class Writer implements CommitTable.Writer {
        @Override
        public void addCommittedTransaction(long startTimestamp, long commitTimestamp) {
            // In this implementation, we use only one location that represents
            // both the value and the invalidation. Therefore, putIfAbsent is
            // required to make sure the entry was not invalidated.
            table.putIfAbsent(startTimestamp, commitTimestamp);
        }

        @Override
        public void updateLowWatermark(long lowWatermark) throws IOException {
            InMemoryCommitTable.this.lowWatermark = lowWatermark;
        }

        @Override
        public void flush() throws IOException {
            // noop
        }

        @Override
        public void clearWriteBuffer() {
            table.clear();
        }

        @Override
        public void close() {}
    }

    public class Client implements CommitTable.Client {
        @Override
        public ListenableFuture<Optional<CommitTimestamp>> getCommitTimestamp(long startTimestamp) {
            SettableFuture<Optional<CommitTimestamp>> f = SettableFuture.<Optional<CommitTimestamp>> create();
            Long result = table.get(startTimestamp);
            if (result == null) {
                f.set(Optional.<CommitTimestamp> absent());
            } else {
                if (result == INVALID_TRANSACTION_MARKER) {
                    f.set(Optional.<CommitTimestamp> of(new CommitTimestamp(Location.COMMIT_TABLE,
                            INVALID_TRANSACTION_MARKER, false)));
                } else {
                    f.set(Optional.<CommitTimestamp> of(new CommitTimestamp(Location.COMMIT_TABLE, result, true)));
                }
            }
            return f;
        }

        @Override
        public ListenableFuture<Long> readLowWatermark() {
            SettableFuture<Long> f = SettableFuture.<Long> create();
            f.set(lowWatermark);
            return f;
        }

        @Override
        public ListenableFuture<Void> completeTransaction(long startTimestamp) {
            SettableFuture<Void> f = SettableFuture.<Void>create();
            table.remove(startTimestamp);
            f.set(null);
            return f;
        }

        @Override
        public ListenableFuture<Boolean> tryInvalidateTransaction(long startTimestamp) {

            SettableFuture<Boolean> f = SettableFuture.<Boolean>create();
            Long old = table.get(startTimestamp);

            // If the transaction represented by startTimestamp is not in the map
            if (old == null) {
                // Try to invalidate the transaction
                old = table.putIfAbsent(startTimestamp, INVALID_TRANSACTION_MARKER);
                // If we were able to invalidate or someone else invalidate before us
                if (old == null || old == INVALID_TRANSACTION_MARKER) {
                    f.set(true);
                    return f;
                }
            } else {
                // Check if the value we read marked the transaction as invalid
                if (old == INVALID_TRANSACTION_MARKER) {
                    f.set(true);
                    return f;
                }
            }

            // At this point the transaction was already in the map at the beginning
            // of the method or was added right before we tried to invalidate.
            f.set(false);
            return f;
        }

        @Override
        public void close() {}
    }
}