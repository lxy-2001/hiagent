package com.agentflow.web.conversation;

import com.agentflow.core.context.ContextSeed;
import com.agentflow.core.context.ContextSource;
import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.core.context.ConversationTurn;
import com.agentflow.core.cancel.CancellationSignal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One bounded, repeatable-read snapshot of successful conversation facts. */
public class PersistentContextSource implements ContextSource {
    private final EntityManager entityManager;
    private final ContextTextPolicy textPolicy;

    public PersistentContextSource(EntityManager entityManager, ContextTextPolicy textPolicy) {
        this.entityManager = Objects.requireNonNull(entityManager);
        this.textPolicy = Objects.requireNonNull(textPolicy);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 2)
    public ContextSeed load(Query query, Duration timeout, CancellationSignal cancellation) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(cancellation);
        long started = System.nanoTime();
        long allowance = ContextSource.bounded(timeout).toNanos();
        try {
            var current = timed(entityManager.createQuery("select t.turnSequence as sequence, t.status as status "
                            + "from AgentTaskEntity t where t.id=:run and t.sessionId=:session and t.userId=:owner", Tuple.class)
                    .setParameter("run", query.currentRunId()).setParameter("session", query.sessionId())
                    .setParameter("owner", query.userId()), started, allowance, cancellation).getResultList();
            if (current.size() != 1 || !"RUNNING".equals(current.get(0).get("status"))) throw unavailable();
            long sequence = ((Number) current.get(0).get("sequence")).longValue();
            if (sequence < 1) throw unavailable();
            long through = sequence - 1;
            var candidates = timed(entityManager.createQuery("select t.id as id, t.turnSequence as sequence, "
                            + "length(t.userInput) as inputLength, length(t.finalAnswer) as answerLength "
                            + "from AgentTaskEntity t where t.sessionId=:session and t.userId=:owner "
                            + "and t.status='SUCCEEDED' and t.turnSequence<:current order by t.turnSequence desc", Tuple.class)
                    .setParameter("session", query.sessionId()).setParameter("owner", query.userId())
                    .setParameter("current", sequence).setMaxResults(21), started, allowance, cancellation).getResultList();
            var turns = new ArrayList<ConversationTurn>();
            int discarded = 0;
            for (var candidate : candidates.subList(0, Math.min(20, candidates.size()))) {
                checkDeadline(started, allowance, cancellation);
                if (!fits(candidate.get("inputLength"), 8000) || !fits(candidate.get("answerLength"), 65536)) {
                    discarded++;
                    continue;
                }
                var row = timed(entityManager.createQuery("select t.userInput as input, t.finalAnswer as answer "
                                + "from AgentTaskEntity t where t.id=:id and t.userId=:owner and t.sessionId=:session "
                                + "and length(t.userInput)<=8000 and length(t.finalAnswer)<=65536", Tuple.class)
                        .setParameter("id", candidate.get("id")).setParameter("owner", query.userId())
                        .setParameter("session", query.sessionId()), started, allowance, cancellation).getResultList();
                if (row.size() != 1) throw unavailable();
                String input = row.get(0).get("input", String.class);
                String answer = row.get(0).get("answer", String.class);
                if (input == null || answer == null || input.length() > 8000 || answer.length() > 65536) {
                    discarded++;
                    continue;
                }
                input = textPolicy.sanitizeHistory(input);
                answer = textPolicy.sanitizeHistory(answer);
                if (input.length() > 8000 || answer.length() > 65536) {
                    discarded++;
                    continue;
                }
                turns.add(new ConversationTurn(candidate.get("id", String.class),
                        ((Number) candidate.get("sequence")).longValue(), input, answer));
            }
            Collections.reverse(turns);
            checkDeadline(started, allowance, cancellation);
            return new ContextSeed(through, turns, List.of(), candidates.size() > 20, discarded);
        } catch (ContextSourceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ContextSourceException("CONTEXT_SOURCE_UNAVAILABLE", failure);
        }
    }

    private static boolean fits(Object size, int maximum) {
        return size instanceof Number number && number.longValue() >= 0 && number.longValue() <= maximum;
    }

    private static <T> TypedQuery<T> timed(TypedQuery<T> query, long started, long allowance, CancellationSignal cancellation) {
        long remaining = checkDeadline(started, allowance, cancellation);
        query.setHint("jakarta.persistence.query.timeout", (int) Math.max(1, (remaining + 999999) / 1000000));
        return query;
    }

    private static long checkDeadline(long started, long allowance, CancellationSignal cancellation) {
        long elapsed = System.nanoTime() - started;
        if (cancellation.isCancelled() || elapsed < 0 || elapsed >= allowance) throw unavailable();
        return allowance - elapsed;
    }

    private static ContextSourceException unavailable() {
        return new ContextSourceException("CONTEXT_SOURCE_UNAVAILABLE");
    }
}
