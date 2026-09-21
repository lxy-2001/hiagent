package com.agentflow.web.run;

import com.agentflow.web.support.ConversationRaceFixture;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.agentflow.web.support.ConversationRaceFixture.Boundary.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationRaceFixtureTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void eachBoundaryRequiresItsOwnRelease() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var fixture = new ConversationRaceFixture()) {
            try {
                var future = executor.submit(() -> {
                    fixture.reach(PREPARATION);
                    fixture.reach(COMMIT);
                    fixture.reach(WORKER_EXIT);
                    return "returned";
                });
                assertThat(fixture.awaitReached(PREPARATION, WAIT)).isTrue();
                assertThat(fixture.awaitReached(COMMIT, Duration.ZERO)).isFalse();
                fixture.release(PREPARATION);
                assertThat(fixture.awaitReached(COMMIT, WAIT)).isTrue();
                assertThat(fixture.awaitReached(WORKER_EXIT, Duration.ZERO)).isFalse();
                fixture.release(COMMIT);
                assertThat(fixture.awaitReached(WORKER_EXIT, WAIT)).isTrue();
                assertThat(future.isDone()).isFalse();
                fixture.release(WORKER_EXIT);
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("returned");
            } finally {
                fixture.close();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void configuredFailureIsRaisedOnlyAtItsBoundary() {
        var failure = new IllegalStateException("controlled source failure");
        try (var fixture = new ConversationRaceFixture(WAIT, Map.of(PREPARATION, failure))) {
            fixture.release(PREPARATION);
            fixture.release(COMMIT);
            fixture.reach(COMMIT);
            assertThatThrownBy(() -> fixture.reach(PREPARATION)).isSameAs(failure);
        }
    }

    @Test
    void closeReleasesBlockedAndNotYetReachedBoundaries() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var fixture = new ConversationRaceFixture();
        try {
            var future = executor.submit(() -> {
                fixture.reach(PREPARATION);
                fixture.reach(COMMIT);
                fixture.reach(WORKER_EXIT);
            });
            assertThat(fixture.awaitReached(PREPARATION, WAIT)).isTrue();
            fixture.close();
            future.get(5, TimeUnit.SECONDS);
            assertThat(fixture.awaitReached(WORKER_EXIT, Duration.ZERO)).isTrue();
        } finally {
            fixture.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void forgottenReleaseFailsInsteadOfHangingIndefinitely() {
        try (var fixture = new ConversationRaceFixture(Duration.ofMillis(1), Map.of())) {
            assertThatThrownBy(() -> fixture.reach(PREPARATION))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not released");
        }
    }

    @Test
    void interruptedWaitTerminatesAndPreservesTheInterruptFlag() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        try (var fixture = new ConversationRaceFixture()) {
            var worker = new Thread(() -> {
                try {
                    fixture.reach(PREPARATION);
                } catch (RuntimeException exception) {
                    failure.set(exception);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            }, "conversation-fixture-interrupt-test");
            try {
                worker.start();
                assertThat(fixture.awaitReached(PREPARATION, WAIT)).isTrue();
                worker.interrupt();
                worker.join(5000);
                assertThat(worker.isAlive()).isFalse();
                assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(interrupted.get()).isTrue();
            } finally {
                fixture.close();
                worker.interrupt();
                worker.join(5000);
                assertThat(worker.isAlive()).isFalse();
            }
        }
    }

    @Test
    void commitGateBlocksARealTransactionBeforeItsRowBecomesVisible() throws Exception {
        var database = database();
        var executor = Executors.newSingleThreadExecutor();
        try (var fixture = new ConversationRaceFixture()) {
            try {
                var fault = new CommitFaultFixture(CommitFaultFixture.Mode.NONE);
                var future = executor.submit(() -> fault.execute(database.transactions(), () -> {
                    database.insert();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) { fixture.reach(COMMIT); }
                    });
                    return "committed";
                }));
                assertThat(fixture.awaitReached(COMMIT, WAIT)).isTrue();
                assertThat(database.rowCount()).isZero();
                fixture.release(COMMIT);
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("committed");
                assertThat(database.rowCount()).isEqualTo(1);
            } finally {
                fixture.close();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            database.jdbc().execute("shutdown");
        }
    }

    @ParameterizedTest
    @EnumSource(value = CommitFaultFixture.Mode.class,
            names = {"ROLLBACK_BEFORE_COMMIT", "LOSE_CONFIRMATION_AFTER_COMMIT"})
    void composesWithExistingTransactionFaultsWithoutSimulatingCommit(CommitFaultFixture.Mode mode) {
        var database = database();
        try (var fixture = new ConversationRaceFixture()) {
            fixture.release(PREPARATION);
            fixture.reach(PREPARATION);
            var fault = new CommitFaultFixture(mode);
            assertThatThrownBy(() -> fault.execute(database.transactions(), database::insert))
                    .isInstanceOf(mode == CommitFaultFixture.Mode.ROLLBACK_BEFORE_COMMIT
                            ? CommitFaultFixture.BeforeCommitFailure.class
                            : CommitFaultFixture.CommitOutcomeUnknownException.class);
            assertThat(database.rowCount()).isEqualTo(mode == CommitFaultFixture.Mode.ROLLBACK_BEFORE_COMMIT ? 0 : 1);
        } finally {
            database.jdbc().execute("shutdown");
        }
    }

    private static Database database() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table fixture_row (id integer primary key)");
        return new Database(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private record Database(JdbcTemplate jdbc, TransactionTemplate transactions) {
        int insert() { return jdbc.update("insert into fixture_row(id) values (1)"); }
        int rowCount() { return jdbc.queryForObject("select count(*) from fixture_row", Integer.class); }
    }
}
