package org.mitallast.queue.raft;

import com.google.inject.Inject;
import com.typesafe.config.Config;
import javaslang.collection.HashMap;
import javaslang.collection.Map;
import javaslang.collection.Vector;
import javaslang.concurrent.Future;
import javaslang.concurrent.Promise;
import javaslang.control.Option;
import org.apache.logging.log4j.CloseableThreadContext;
import org.mitallast.queue.common.Match;
import org.mitallast.queue.common.component.AbstractLifecycleComponent;
import org.mitallast.queue.common.events.EventBus;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.raft.cluster.ClusterConfiguration;
import org.mitallast.queue.raft.cluster.StableClusterConfiguration;
import org.mitallast.queue.raft.discovery.ClusterDiscovery;
import org.mitallast.queue.raft.event.MembersChanged;
import org.mitallast.queue.raft.persistent.PersistentService;
import org.mitallast.queue.raft.persistent.ReplicatedLog;
import org.mitallast.queue.raft.protocol.*;
import org.mitallast.queue.raft.resource.ResourceRegistry;
import org.mitallast.queue.transport.DiscoveryNode;
import org.mitallast.queue.transport.TransportController;
import org.mitallast.queue.transport.TransportService;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.mitallast.queue.raft.RaftState.*;

public class Raft extends AbstractLifecycleComponent {
    private final TransportService transportService;
    private final TransportController transportController;
    private final ClusterDiscovery clusterDiscovery;
    private final PersistentService persistentService;
    private final ReplicatedLog replicatedLog;
    private final ResourceRegistry registry;
    private final boolean bootstrap;
    private final long electionDeadline;
    private final long heartbeat;
    private final int snapshotInterval;
    private final int maxEntries;
    private final RaftContext context;
    private final EventBus eventBus;
    private final ConcurrentLinkedQueue<ClientMessage> stashed;
    private final ConcurrentHashMap<Long, Promise<Streamable>> sessionCommands;
    private final ReentrantLock lock;
    private volatile Option<DiscoveryNode> recentlyContactedByLeader;
    private volatile Map<DiscoveryNode, Long> replicationIndex;
    private volatile LogIndexMap nextIndex;
    private volatile LogIndexMap matchIndex;
    private volatile State state;

    private final Match.Mapper<Streamable, State> mapper = Match.<Streamable, State>map()
        .when(AppendEntries.class, (e) -> state.handle(e))
        .when(AppendRejected.class, (e) -> state.handle(e))
        .when(AppendSuccessful.class, (e) -> state.handle(e))
        .when(RequestVote.class, (e) -> state.handle(e))
        .when(VoteCandidate.class, (e) -> state.handle(e))
        .when(DeclineCandidate.class, (e) -> state.handle(e))
        .when(ClientMessage.class, (e) -> state.handle(e))
        .when(InstallSnapshot.class, (e) -> state.handle(e))
        .when(InstallSnapshotSuccessful.class, (e) -> state.handle(e))
        .when(InstallSnapshotRejected.class, (e) -> state.handle(e))
        .when(AddServer.class, (e) -> state.handle(e))
        .when(AddServerResponse.class, (e) -> state.handle(e))
        .when(RemoveServer.class, (e) -> state.handle(e))
        .when(RemoveServerResponse.class, (e) -> state.handle(e))
        .build();

    @Inject
    public Raft(
        Config config,
        TransportService transportService,
        TransportController transportController,
        ClusterDiscovery clusterDiscovery,
        PersistentService persistentService,
        ResourceRegistry registry,
        RaftContext context,
        EventBus eventBus
    ) {
        this.transportService = transportService;
        this.transportController = transportController;
        this.clusterDiscovery = clusterDiscovery;
        this.persistentService = persistentService;
        this.replicatedLog = persistentService.openLog();
        this.registry = registry;
        this.context = context;
        this.eventBus = eventBus;

        stashed = new ConcurrentLinkedQueue<>();
        sessionCommands = new ConcurrentHashMap<>();
        lock = new ReentrantLock();
        recentlyContactedByLeader = Option.none();
        nextIndex = new LogIndexMap(0);
        matchIndex = new LogIndexMap(0);

        bootstrap = config.getBoolean("raft.bootstrap");
        electionDeadline = config.getDuration("raft.election-deadline", TimeUnit.MILLISECONDS);
        heartbeat = config.getDuration("raft.heartbeat", TimeUnit.MILLISECONDS);
        snapshotInterval = config.getInt("raft.snapshot-interval");
        maxEntries = config.getInt("raft.max-entries");
    }

    @Override
    protected void doStart() {
        RaftMetadata meta = new RaftMetadata(
            persistentService.currentTerm(),
            new StableClusterConfiguration(),
            persistentService.votedFor()
        );
        state = new FollowerState(meta).initialize();
    }

    @Override
    protected void doStop() {
        context.cancelTimer(RaftContext.ELECTION_TIMEOUT);
        context.cancelTimer(RaftContext.SEND_HEARTBEAT);
    }

    @Override
    protected void doClose() {
    }

    // fsm related

    public void apply(Streamable event) {
        lock.lock();
        try {
            if (state == null) {
                return;
            }
            try (final CloseableThreadContext.Instance ignored = CloseableThreadContext.push(state.state().name())) {
                state = mapper.apply(event);
            }
        } finally {
            lock.unlock();
        }
    }

    public Future<Streamable> command(Streamable cmd) {
        Promise<Streamable> promise = Promise.make();
        Promise<Streamable> prev;
        long session;
        do {
            session = ThreadLocalRandom.current().nextLong();
            prev = sessionCommands.putIfAbsent(session, promise);
        } while (prev != null);
        if (logger.isInfoEnabled()) {
            logger.info("client command with session {}", session);
        }
        ClientMessage clientMessage = new ClientMessage(
            cmd,
            session
        );
        apply(clientMessage);
        return promise.future();
    }

    public Option<DiscoveryNode> recentLeader() {
        return recentlyContactedByLeader;
    }

    public RaftState currentState() {
        return state.state();
    }

    public RaftMetadata currentMeta() {
        return state.meta();
    }

    public ReplicatedLog replicatedLog() {
        return replicatedLog;
    }

    public Vector<Streamable> currentStashed() {
        return Vector.ofAll(stashed);
    }

    // behavior related

    private void send(DiscoveryNode node, Streamable message) {
        if (node.equals(clusterDiscovery.self())) {
            transportController.dispatch(message);
        } else {
            transportService.send(node, message);
        }
    }

    private void senderIsCurrentLeader(DiscoveryNode leader) {
        if (logger.isDebugEnabled()) {
            logger.debug("leader is {}", leader);
        }
        recentlyContactedByLeader = Option.some(leader);
    }

    // additional classes

    private abstract class State {
        private RaftMetadata meta;

        private State(RaftMetadata meta) {
            this.meta = meta;
            persistentService.updateState(meta.getCurrentTerm(), meta.getVotedFor());
            state = this;
        }

        protected State stay(RaftMetadata meta) {
            RaftMetadata prev = this.meta;
            this.meta = meta;
            persistentService.updateState(meta.getCurrentTerm(), meta.getVotedFor());
            if (prev.getConfig() != meta.getConfig() && !meta.getConfig().isTransitioning()) {
                eventBus.trigger(new MembersChanged(meta.members()));
            }
            return this;
        }

        public abstract RaftState state();

        public RaftMetadata meta() {
            return meta;
        }

        // replication

        public abstract State handle(AppendEntries message);

        public State handle(AppendRejected message) {
            if (logger.isDebugEnabled()) {
                logger.debug("unhandled: {} in {}", message, state());
            }
            return this;
        }

        public State handle(AppendSuccessful message) {
            if (logger.isDebugEnabled()) {
                logger.debug("unhandled: {} in {}", message, state());
            }
            return this;
        }

        // election

        public abstract State handle(RequestVote message);

        public State handle(VoteCandidate message) {
            if (logger.isDebugEnabled()) {
                logger.debug("unhandled: {} in {}", message, state());
            }
            return this;
        }

        public State handle(DeclineCandidate message) {
            if (logger.isDebugEnabled()) {
                logger.debug("unhandled: {} in {}", message, state());
            }
            return this;
        }

        // leader

        public abstract State handle(ClientMessage message);

        // snapshot

        public State createSnapshot() {
            long committedIndex = replicatedLog.committedIndex();
            RaftSnapshotMetadata snapshotMeta = new RaftSnapshotMetadata(replicatedLog.termAt(committedIndex),
                committedIndex, meta().getConfig());
            if (logger.isInfoEnabled()) {
                logger.info("init snapshot up to: {}:{}", snapshotMeta.getLastIncludedIndex(),
                    snapshotMeta.getLastIncludedTerm());
            }

            RaftSnapshot snapshot = registry.prepareSnapshot(snapshotMeta);
            if (logger.isInfoEnabled()) {
                logger.info("successfully prepared snapshot for {}:{}, compacting log now",
                    snapshotMeta.getLastIncludedIndex(), snapshotMeta.getLastIncludedTerm());
            }
            replicatedLog.compactWith(snapshot);

            return this;
        }

        public abstract State handle(InstallSnapshot message);

        public State handle(InstallSnapshotSuccessful message) {
            if (logger.isDebugEnabled()) {
                logger.debug("unhandled: {} in {}", message, state());
            }
            return this;
        }

        public State handle(InstallSnapshotRejected message) {
            if (logger.isDebugEnabled()) {
                logger.debug("unhandled: {} in {}", message, state());
            }
            return this;
        }

        // joint consensus

        public abstract State handle(AddServer request);

        public State handle(AddServerResponse message) {
            if (logger.isDebugEnabled()) {
                logger.debug("unhandled: {} in {}", message, state());
            }
            return this;
        }

        public abstract State handle(RemoveServer request);

        public State handle(RemoveServerResponse message) {
            if (logger.isDebugEnabled()) {
                logger.debug("unhandled: {} in {}", message, state());
            }
            return this;
        }

        // stash messages

        public void stash(ClientMessage streamable) {
            if (logger.isDebugEnabled()) {
                logger.debug("stash {}", streamable);
            }
            stashed.add(streamable);
        }
    }

    private class FollowerState extends State {

        public FollowerState(RaftMetadata meta) {
            super(meta);
        }

        @Override
        public RaftState state() {
            return Follower;
        }

        protected FollowerState stay(RaftMetadata meta) {
            super.stay(meta);
            return this;
        }

        private State gotoCandidate() {
            resetElectionDeadline();
            return new CandidateState(this.meta().forNewElection()).beginElection();
        }

        public FollowerState resetElectionDeadline() {
            if (logger.isDebugEnabled()) {
                logger.debug("reset election deadline");
            }
            long timeout = new Random().nextInt((int) (electionDeadline / 2)) + electionDeadline;
            context.setTimer(RaftContext.ELECTION_TIMEOUT, timeout, () -> {
                lock.lock();
                try {
                    if (state == this) {
                        state = electionTimeout();
                    } else {
                        throw new IllegalStateException();
                    }
                } catch (IllegalStateException e) {
                    logger.error("error handle election timeout", e);
                } finally {
                    lock.unlock();
                }
            });
            return this;
        }

        public State initialize() {
            resetElectionDeadline();
            if (replicatedLog.isEmpty()) {
                if (bootstrap) {
                    if (logger.isInfoEnabled()) {
                        logger.info("bootstrap cluster");
                    }
                    return handle(new AddServer(clusterDiscovery.self()));
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info("joint cluster");
                    }
                    return electionTimeout();
                }
            } else {
                ClusterConfiguration config = replicatedLog.entries()
                    .map(LogEntry::command)
                    .filter(cmd -> cmd instanceof ClusterConfiguration)
                    .map(cmd -> (ClusterConfiguration) cmd)
                    .reduceOption((a, b) -> b)
                    .getOrElse(meta().getConfig());

                RaftMetadata meta = meta().withConfig(config).withTerm(replicatedLog.lastTerm());
                return stay(meta);
            }
        }

        @Override
        @SuppressWarnings("ConstantConditions")
        public State handle(ClientMessage message) {
            if (recentlyContactedByLeader.isDefined()) {
                send(recentlyContactedByLeader.get(), message);
            } else {
                stash(message);
            }
            return this;
        }

        @Override
        public State handle(RequestVote message) {
            RaftMetadata meta = meta();
            if (message.getTerm() > meta.getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received newer {}, current term is {}", message.getTerm(), meta.getCurrentTerm());
                }
                meta = meta.withTerm(message.getTerm());
            }
            if (meta.canVoteIn(message.getTerm())) {
                resetElectionDeadline();
                if (replicatedLog.lastTerm().exists(term -> message.getLastLogTerm() < term)) {
                    logger.warn("rejecting vote for {} at term {}, candidate's lastLogTerm: {} < ours: {}",
                        message.getCandidate(),
                        message.getTerm(),
                        message.getLastLogTerm(),
                        replicatedLog.lastTerm());
                    send(message.getCandidate(), new DeclineCandidate(clusterDiscovery.self(), meta.getCurrentTerm()));
                    return stay(meta);
                }
                if (replicatedLog.lastTerm().exists(term -> term.equals(message.getLastLogTerm())) &&
                    message.getLastLogIndex() < replicatedLog.lastIndex()) {
                    logger.warn("rejecting vote for {} at term {}, candidate's lastLogIndex: {} < ours: {}",
                        message.getCandidate(),
                        message.getTerm(),
                        message.getLastLogIndex(),
                        replicatedLog.lastIndex());
                    send(message.getCandidate(), new DeclineCandidate(clusterDiscovery.self(), meta.getCurrentTerm()));
                    return stay(meta);
                }

                if (logger.isInfoEnabled()) {
                    logger.info("voting for {} in {}", message.getCandidate(), message.getTerm());
                }
                send(message.getCandidate(), new VoteCandidate(clusterDiscovery.self(), meta.getCurrentTerm()));
                return stay(meta.withVoteFor(message.getCandidate()));
            } else if (meta.getVotedFor().isDefined()) {
                logger.warn("rejecting vote for {}, and {}, currentTerm: {}, already voted for: {}",
                    message.getCandidate(),
                    message.getTerm(),
                    meta.getCurrentTerm(),
                    meta.getVotedFor());
                send(message.getCandidate(), new DeclineCandidate(clusterDiscovery.self(), meta.getCurrentTerm()));
                return stay(meta);
            } else {
                logger.warn("rejecting vote for {}, and {}, currentTerm: {}, received stale term number {}",
                    message.getCandidate(), message.getTerm(),
                    meta.getCurrentTerm(), message.getTerm());
                send(message.getCandidate(), new DeclineCandidate(clusterDiscovery.self(), meta.getCurrentTerm()));
                return stay(meta);
            }
        }

        @Override
        public State handle(AppendEntries message) {
            RaftMetadata meta = meta();
            if (message.getTerm() > meta.getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received newer {}, current term is {}", message.getTerm(), meta.getCurrentTerm());
                }
                meta = meta.withTerm(message.getTerm());
            }
            // 1) Reply false if term < currentTerm (5.1)
            if (message.getTerm() < meta.getCurrentTerm()) {
                logger.warn("rejecting write (old term): {} < {} ", message.getTerm(), meta.getCurrentTerm());
                send(message.getMember(), new AppendRejected(clusterDiscovery.self(), meta.getCurrentTerm(),
                    replicatedLog.lastIndex()));
                return stay(meta);
            }

            try {
                // 2) Reply false if log doesn’t contain an entry at prevLogIndex whose term matches prevLogTerm (5.3)
                if (!replicatedLog.containsMatchingEntry(message.getPrevLogTerm(), message.getPrevLogIndex())) {
                    logger.warn("rejecting write (inconsistent log): {}:{} {} ",
                        message.getPrevLogTerm(), message.getPrevLogIndex(),
                        replicatedLog);
                    send(message.getMember(), new AppendRejected(clusterDiscovery.self(), meta.getCurrentTerm(),
                        replicatedLog.lastIndex()));
                    return stay(meta);
                } else {
                    return appendEntries(message, meta);
                }
            } finally {
                resetElectionDeadline();
            }
        }

        private State appendEntries(AppendEntries msg, RaftMetadata meta) {
            senderIsCurrentLeader(msg.getMember());

            if (!msg.getEntries().isEmpty()) {
                // If an existing entry conflicts with a new one (same index
                // but different terms), delete the existing entry and all that
                // follow it (5.3)

                // Append any new entries not already in the log

                if (logger.isDebugEnabled()) {
                    logger.debug("append({})", msg.getEntries());
                }
                replicatedLog.append(msg.getEntries());
            }
            if (logger.isDebugEnabled()) {
                logger.debug("response append successful term:{} lastIndex:{}", meta.getCurrentTerm(), replicatedLog.lastIndex());
            }
            AppendSuccessful response = new AppendSuccessful(clusterDiscovery.self(), meta.getCurrentTerm(), replicatedLog.lastIndex());
            send(msg.getMember(), response);

            // If leaderCommit > commitIndex, set commitIndex = min(leaderCommit, index of last new entry)

            if (msg.getLeaderCommit() > replicatedLog.committedIndex()) {
                Vector<LogEntry> entries = replicatedLog.slice(replicatedLog.committedIndex() + 1, msg.getLeaderCommit());
                for (LogEntry entry : entries) {
                    if (entry.command() instanceof ClusterConfiguration) {
                        if (logger.isInfoEnabled()) {
                            logger.info("apply new configuration: {}", entry.command());
                        }
                        meta = meta.withConfig((ClusterConfiguration) entry.command());
                    } else if (entry.command() instanceof Noop) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("ignore noop entry");
                        }
                    } else if (entry.command() instanceof RaftSnapshot) {
                        logger.warn("unexpected raft snapshot in log");
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("committing entry {} on follower, leader is committed until [{}]", entry, msg.getLeaderCommit());
                        }
                        Streamable result = registry.apply(entry.index(), entry.command());
                        if (result != null) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("success client command session {}", entry.session());
                            }
                            Promise<Streamable> promise = sessionCommands.remove(entry.session());
                            if (promise != null) {
                                promise.success(result);
                            }
                        }
                    }
                    replicatedLog.commit(entry.index());
                }
            }

            ClusterConfiguration config = msg.getEntries()
                .map(LogEntry::command)
                .filter(cmd -> cmd instanceof ClusterConfiguration)
                .map(cmd -> (ClusterConfiguration) cmd)
                .reduceOption((a, b) -> b)
                .getOrElse(meta.getConfig());

            FollowerState newState = stay(meta.withTerm(replicatedLog.lastTerm()).withConfig(config));
            newState.unstash();
            if (replicatedLog.committedEntries() >= snapshotInterval) {
                return newState.createSnapshot();
            } else {
                return newState;
            }
        }

        public State electionTimeout() {
            resetElectionDeadline();
            if (meta().getConfig().members().isEmpty()) {
                if (logger.isInfoEnabled()) {
                    logger.info("no members found, joint timeout");
                }
                for (DiscoveryNode node : clusterDiscovery.discoveryNodes()) {
                    if (!node.equals(clusterDiscovery.self())) {
                        send(node, new AddServer(clusterDiscovery.self()));
                    }
                }
                return this;
            } else {
                return gotoCandidate();
            }
        }

        // joint consensus

        @Override
        public State handle(AddServer request) {
            if (bootstrap && meta().getConfig().members().isEmpty() &&
                request.getMember().equals(clusterDiscovery.self()) &&
                replicatedLog.isEmpty()) {
                context.cancelTimer(RaftContext.ELECTION_TIMEOUT);
                return new LeaderState(this.meta()).selfJoin();
            }
            send(request.getMember(), new AddServerResponse(
                AddServerResponse.Status.NOT_LEADER,
                recentlyContactedByLeader
            ));
            return this;
        }

        public State handle(AddServerResponse request) {
            if (request.getStatus() == AddServerResponse.Status.OK) {
                if (logger.isInfoEnabled()) {
                    logger.info("successful joined");
                }
            }
            Option<DiscoveryNode> leader = request.getLeader();
            if (leader.isDefined()) {
                senderIsCurrentLeader(leader.get());
                resetElectionDeadline();
            }
            return this;
        }

        @Override
        public State handle(RemoveServer request) {
            send(request.getMember(), new RemoveServerResponse(
                RemoveServerResponse.Status.NOT_LEADER,
                recentlyContactedByLeader
            ));
            return this;
        }

        public State handle(RemoveServerResponse request) {
            if (request.getStatus() == RemoveServerResponse.Status.OK) {
                if (logger.isInfoEnabled()) {
                    logger.info("successful removed");
                }
            }
            recentlyContactedByLeader = request.getLeader();
            resetElectionDeadline();
            return this;
        }

        @Override
        public State handle(InstallSnapshot message) {
            RaftMetadata meta = meta();
            if (message.getTerm() > meta.getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received newer {}, current term is {}", message.getTerm(), meta.getCurrentTerm());
                }
                meta = meta.withTerm(message.getTerm());
            }
            if (message.getTerm() < meta.getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("rejecting install snapshot {}, current term is {}", message.getTerm(), meta.getCurrentTerm());
                }
                send(message.getLeader(), new InstallSnapshotRejected(clusterDiscovery.self(), meta.getCurrentTerm()));
                return stay(meta);
            } else {
                resetElectionDeadline();
                if (logger.isInfoEnabled()) {
                    logger.info("got snapshot from {}, is for: {}", message.getLeader(), message.getSnapshot().getMeta());
                }

                meta = meta.withConfig(message.getSnapshot().getMeta().getConfig());
                replicatedLog.compactWith(message.getSnapshot());
                for (Streamable streamable : message.getSnapshot().getData()) {
                    registry.apply(message.getSnapshot().getMeta().getLastIncludedIndex(), streamable);
                }

                if (logger.isInfoEnabled()) {
                    logger.info("response snapshot installed in {} last index {}", meta.getCurrentTerm(),
                        replicatedLog.lastIndex());
                }
                send(message.getLeader(), new InstallSnapshotSuccessful(clusterDiscovery.self(),
                    meta.getCurrentTerm(), replicatedLog.lastIndex()));

                return stay(meta);
            }
        }

        @SuppressWarnings("ConstantConditions")
        private void unstash() {
            if (recentlyContactedByLeader.isDefined()) {
                DiscoveryNode leader = recentlyContactedByLeader.get();
                Streamable poll;
                while ((poll = stashed.poll()) != null) {
                    send(leader, poll);
                }
            } else {
                logger.warn("try unstash without leader");
            }
        }
    }

    private class CandidateState extends State {

        public CandidateState(RaftMetadata meta) {
            super(meta);
        }

        @Override
        public RaftState state() {
            return Candidate;
        }

        @Override
        protected CandidateState stay(RaftMetadata meta) {
            super.stay(meta);
            return this;
        }

        private FollowerState gotoFollower() {
            return new FollowerState(this.meta().forFollower()).resetElectionDeadline();
        }

        public State gotoLeader() {
            context.cancelTimer(RaftContext.ELECTION_TIMEOUT);
            return new LeaderState(this.meta()).elected();
        }

        @Override
        public State handle(ClientMessage message) {
            stash(message);
            return this;
        }

        @Override
        public State handle(AddServer request) {
            send(request.getMember(), new AddServerResponse(
                AddServerResponse.Status.NOT_LEADER,
                Option.none()
            ));
            return this;
        }

        @Override
        public State handle(RemoveServer request) {
            send(request.getMember(), new RemoveServerResponse(
                RemoveServerResponse.Status.NOT_LEADER,
                Option.none()
            ));
            return this;
        }

        private void resetElectionDeadline() {
            if (logger.isDebugEnabled()) {
                logger.debug("reset election deadline");
            }
            long timeout = new Random().nextInt((int) (electionDeadline / 2)) + electionDeadline;
            context.setTimer(RaftContext.ELECTION_TIMEOUT, timeout, () -> {
                lock.lock();
                try {
                    if (state == this) {
                        state = electionTimeout();
                    } else {
                        throw new IllegalStateException();
                    }
                } catch (IllegalStateException e) {
                    logger.error("error handle election timeout", e);
                } finally {
                    lock.unlock();
                }
            });
        }

        private State beginElection() {
            resetElectionDeadline();
            RaftMetadata meta = meta();
            if (logger.isInfoEnabled()) {
                logger.info("initializing election (among {} nodes) for {}", meta.getConfig().members().size(), meta.getCurrentTerm());
            }
            RequestVote request = new RequestVote(meta.getCurrentTerm(), clusterDiscovery.self(),
                replicatedLog.lastTerm().getOrElse(0L), replicatedLog.lastIndex());
            for (DiscoveryNode member : meta.membersWithout(clusterDiscovery.self())) {
                if (logger.isInfoEnabled()) {
                    logger.info("send request vote to {}", member);
                }
                send(member, request);
            }
            meta = meta.incVote().withVoteFor(clusterDiscovery.self());
            if (meta.hasMajority()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received vote by {}, won election with {} of {} votes", clusterDiscovery.self(),
                        meta.getVotesReceived(), meta.getConfig().members().size());
                }
                return stay(meta).gotoLeader();
            } else {
                return stay(meta);
            }
        }

        @Override
        public State handle(RequestVote message) {
            if (message.getTerm() < meta().getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("rejecting request vote msg by {} in {}, received stale {}.", message.getCandidate(),
                        meta().getCurrentTerm(), message.getTerm());
                }
                send(message.getCandidate(), new DeclineCandidate(clusterDiscovery.self(), meta().getCurrentTerm()));
                return this;
            }
            if (message.getTerm() > meta().getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received newer {}, current term is {}, revert to follower state.", message.getTerm(),
                        meta().getCurrentTerm());
                }
                return stay(meta().withTerm(message.getTerm())).gotoFollower().handle(message);
            }
            if (logger.isInfoEnabled()) {
                logger.info("rejecting requestVote msg by {} in {}, already voted for {}", message.getCandidate(),
                    meta().getCurrentTerm(), meta().getVotedFor());
            }
            send(message.getCandidate(), new DeclineCandidate(clusterDiscovery.self(), meta().getCurrentTerm()));
            return this;
        }

        @Override
        public State handle(VoteCandidate message) {
            RaftMetadata meta = meta();
            if (message.getTerm() < meta.getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("ignore vote candidate msg by {} in {}, received stale {}.", message.getMember(),
                        meta.getCurrentTerm(), message.getTerm());
                }
                return this;
            }
            if (message.getTerm() > meta.getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received newer {}, current term is {}, revert to follower state.", message.getTerm(),
                        meta.getCurrentTerm());
                }
                return stay(meta.withTerm(message.getTerm())).gotoFollower();
            }

            meta = meta.incVote();
            if (meta.hasMajority()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received vote by {}, won election with {} of {} votes", message.getMember(),
                        meta.getVotesReceived(), meta.getConfig().members().size());
                }
                return stay(meta).gotoLeader();
            } else {
                if (logger.isInfoEnabled()) {
                    logger.info("received vote by {}, have {} of {} votes", message.getMember(), meta.getVotesReceived(),
                        meta.getConfig().members().size());
                }
                return stay(meta);
            }
        }

        @Override
        public State handle(DeclineCandidate message) {
            if (message.getTerm() > meta().getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received newer {}, current term is {}, revert to follower state.", message.getTerm(),
                        meta().getCurrentTerm());
                }
                return stay(meta().withTerm(message.getTerm())).gotoFollower();
            } else {
                if (logger.isInfoEnabled()) {
                    logger.info("candidate is declined by {} in term {}", message.getMember(), meta().getCurrentTerm());
                }
                return this;
            }
        }

        @Override
        public State handle(AppendEntries message) {
            boolean leaderIsAhead = message.getTerm() >= meta().getCurrentTerm();
            if (leaderIsAhead) {
                if (logger.isInfoEnabled()) {
                    logger.info("reverting to follower, because got append entries from leader in {}, but am in {}",
                        message.getTerm(), meta().getCurrentTerm());
                }
                return stay(meta().withTerm(message.getTerm())).gotoFollower().handle(message);
            } else {
                return this;
            }
        }

        @Override
        public State handle(InstallSnapshot message) {
            boolean leaderIsAhead = message.getTerm() >= meta().getCurrentTerm();
            if (leaderIsAhead) {
                if (logger.isInfoEnabled()) {
                    logger.info("reverting to follower, because got install snapshot from leader in {}, but am in {}",
                        message.getTerm(), meta().getCurrentTerm());
                }
                return stay(meta().withTerm(message.getTerm())).gotoFollower().handle(message);
            } else {
                send(message.getLeader(), new InstallSnapshotRejected(clusterDiscovery.self(), meta().getCurrentTerm()));
                return this;
            }
        }

        public State electionTimeout() {
            if (logger.isInfoEnabled()) {
                logger.info("voting timeout, starting a new election (among {})", meta().getConfig().members().size());
            }
            return stay(meta().forNewElection()).beginElection();
        }
    }

    private class LeaderState extends State {

        public LeaderState(RaftMetadata meta) {
            super(meta);
        }

        @Override
        public RaftState state() {
            return Leader;
        }

        @Override
        protected LeaderState stay(RaftMetadata meta) {
            super.stay(meta);
            return this;
        }

        private State gotoFollower() {
            context.cancelTimer(RaftContext.SEND_HEARTBEAT);
            return new FollowerState(this.meta().forFollower()).resetElectionDeadline();
        }

        public State selfJoin() {
            if (logger.isInfoEnabled()) {
                logger.info("bootstrap cluster with {}", clusterDiscovery.self());
            }
            RaftMetadata meta = meta()
                .withTerm(meta().getCurrentTerm() + 1)
                .withConfig(new StableClusterConfiguration(clusterDiscovery.self()));

            nextIndex = new LogIndexMap(replicatedLog.lastIndex() + 1);
            matchIndex = new LogIndexMap(0);
            replicationIndex = HashMap.empty();
            final LogEntry entry;
            if (replicatedLog.isEmpty()) {
                entry = new LogEntry(meta.getCurrentTerm(), replicatedLog.nextIndex(), 0, meta.getConfig());
            } else {
                entry = new LogEntry(meta.getCurrentTerm(), replicatedLog.nextIndex(), 0, Noop.INSTANCE);
            }

            replicatedLog.append(entry);
            matchIndex.put(clusterDiscovery.self(), entry.index());

            sendHeartbeat();
            startHeartbeat();

            ClientMessage clientMessage;
            while ((clientMessage = stashed.poll()) != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("appending command: [{}] to replicated log", clientMessage.command());
                }
                LogEntry logEntry = new LogEntry(meta.getCurrentTerm(), replicatedLog.nextIndex(), clientMessage.session(), clientMessage.command());
                replicatedLog.append(logEntry);
                matchIndex.put(clusterDiscovery.self(), entry.index());
            }

            return stay(meta).maybeCommitEntry();
        }

        public State elected() {
            if (logger.isInfoEnabled()) {
                logger.info("became leader for {}", meta().getCurrentTerm());
            }

            // for each server, index of the next log entry
            // to send to that server (initialized to leader
            // last log index + 1)
            nextIndex = new LogIndexMap(replicatedLog.lastIndex() + 1);

            // for each server, index of highest log entry
            // known to be replicated on server
            // (initialized to 0, increases monotonically)
            matchIndex = new LogIndexMap(0);

            // for each server store last send heartbeat time
            // 0 if no response is expected
            replicationIndex = HashMap.empty();

            final LogEntry entry;
            if (replicatedLog.isEmpty()) {
                entry = new LogEntry(meta().getCurrentTerm(), replicatedLog.nextIndex(), 0, meta().getConfig());
            } else {
                entry = new LogEntry(meta().getCurrentTerm(), replicatedLog.nextIndex(), 0, Noop.INSTANCE);
            }

            replicatedLog.append(entry);
            matchIndex.put(clusterDiscovery.self(), entry.index());

            sendHeartbeat();
            startHeartbeat();

            ClientMessage clientMessage;
            while ((clientMessage = stashed.poll()) != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("appending command: [{}] to replicated log", clientMessage.command());
                }
                LogEntry logEntry = new LogEntry(meta().getCurrentTerm(), replicatedLog.nextIndex(),
                    clientMessage.session(), clientMessage.command());
                replicatedLog.append(logEntry);
                matchIndex.put(clusterDiscovery.self(), entry.index());
            }

            return maybeCommitEntry();
        }

        @Override
        public State handle(ClientMessage message) {
            if (logger.isDebugEnabled()) {
                logger.debug("appending command: [{}] to replicated log", message.command());
            }
            LogEntry entry = new LogEntry(meta().getCurrentTerm(), replicatedLog.nextIndex(), message.session(), message.command());
            replicatedLog.append(entry);
            matchIndex.put(clusterDiscovery.self(), entry.index());
            sendHeartbeat();
            return maybeCommitEntry();
        }

        @Override
        public State handle(AppendEntries message) {
            if (message.getTerm() > meta().getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("leader ({}) got append entries from fresher leader ({}), will step down and the leader " +
                        "will keep being: {}", meta().getCurrentTerm(), message.getTerm(), message.getMember());
                }
                return gotoFollower().handle(message);
            } else {
                logger.warn("leader ({}) got append entries from rogue leader ({} @ {}), it's not fresher than self, " +
                        "will send entries, to force it to step down.", meta().getCurrentTerm(), message.getMember(),
                    message.getTerm());
                sendEntries(message.getMember());
                return this;
            }
        }

        @Override
        public State handle(AppendRejected message) {
            if (message.getTerm() > meta().getCurrentTerm()) {
                return stay(meta().withTerm(message.getTerm())).gotoFollower();
            }
            if (message.getTerm() == meta().getCurrentTerm()) {
                long nextIndexFor = nextIndex.indexFor(message.getMember());
                if (nextIndexFor > message.getLastIndex()) {
                    nextIndex.put(message.getMember(), message.getLastIndex());
                } else if (nextIndexFor > 0) {
                    nextIndex.decrementFor(message.getMember());
                }
                logger.warn("follower {} rejected write, term {}, decrement index to {}", message.getMember(),
                    message.getTerm(), nextIndex.indexFor(message.getMember()));
                sendEntries(message.getMember());
                return this;
            } else {
                logger.warn("follower {} rejected write: {}, ignore", message.getMember(), message.getTerm());
                return this;
            }
        }

        @Override
        public State handle(AppendSuccessful message) {
            if (message.getTerm() > meta().getCurrentTerm()) {
                return stay(meta().withTerm(message.getTerm())).gotoFollower();
            }
            if (message.getTerm() == meta().getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received append successful {} in term: {}", message, meta().getCurrentTerm());
                }
                assert (message.getLastIndex() <= replicatedLog.lastIndex());
                if (message.getLastIndex() > 0) {
                    nextIndex.put(message.getMember(), message.getLastIndex() + 1);
                }
                matchIndex.putIfGreater(message.getMember(), message.getLastIndex());
                replicationIndex = replicationIndex.put(message.getMember(), 0L);
                maybeSendEntries(message.getMember());
                return maybeCommitEntry();
            } else {
                logger.warn("unexpected append successful: {} in term:{}", message, meta().getCurrentTerm());
                return this;
            }
        }

        @Override
        public State handle(InstallSnapshot message) {
            if (message.getTerm() > meta().getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("leader ({}) got install snapshot from fresher leader ({}), " +
                            "will step down and the leader will keep being: {}",
                        meta().getCurrentTerm(), message.getTerm(), message.getLeader());
                }
                return stay(meta().withTerm(message.getTerm())).gotoFollower().handle(message);
            } else {
                if (logger.isInfoEnabled()) {
                    logger.info("rejecting install snapshot {}, current term is {}",
                        message.getTerm(), meta().getCurrentTerm());
                }
                logger.warn("leader ({}) got install snapshot from rogue leader ({} @ {}), " +
                        "it's not fresher than self, will send entries, to force it to step down.",
                    meta().getCurrentTerm(), message.getLeader(), message.getTerm());
                sendEntries(message.getLeader());
                return this;
            }
        }

        @Override
        public State handle(InstallSnapshotSuccessful message) {
            if (message.getTerm() > meta().getCurrentTerm()) {
                return stay(meta().withTerm(message.getTerm())).gotoFollower();
            }
            if (message.getTerm() == meta().getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received install snapshot successful[{}], last index[{}]", message.getLastIndex(),
                        replicatedLog.lastIndex());
                }
                assert (message.getLastIndex() <= replicatedLog.lastIndex());
                if (message.getLastIndex() > 0) {
                    nextIndex.put(message.getMember(), message.getLastIndex() + 1);
                }
                matchIndex.putIfGreater(message.getMember(), message.getLastIndex());
                return maybeCommitEntry();
            } else {
                logger.warn("unexpected install snapshot successful: {} in term:{}", message, meta().getCurrentTerm());
                return this;
            }
        }

        @Override
        public State handle(InstallSnapshotRejected message) {
            if (message.getTerm() > meta().getCurrentTerm()) {
                // since there seems to be another leader!
                return stay(meta().withTerm(message.getTerm())).gotoFollower();
            } else if (message.getTerm() == meta().getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("follower {} rejected write: {}, back out the first index in this term and retry",
                        message.getMember(), message.getTerm());
                }
                if (nextIndex.indexFor(message.getMember()) > 1) {
                    nextIndex.decrementFor(message.getMember());
                }
                sendEntries(message.getMember());
                return this;
            } else {
                logger.warn("unexpected install snapshot successful: {} in term:{}", message, meta().getCurrentTerm());
                return this;
            }
        }

        @Override
        public State handle(AddServer request) {
            RaftMetadata meta = meta();
            if (meta.getConfig().isTransitioning()) {
                logger.warn("try add server {} in transitioning state", request.getMember());
                send(request.getMember(), new AddServerResponse(
                    AddServerResponse.Status.TIMEOUT,
                    Option.some(clusterDiscovery.self())
                ));
                return stay(meta);
            } else {
                if (meta.members().contains(request.getMember())) {
                    send(request.getMember(), new AddServerResponse(
                        AddServerResponse.Status.OK,
                        Option.some(clusterDiscovery.self())
                    ));
                    return stay(meta);
                }
                StableClusterConfiguration config = new StableClusterConfiguration(meta.members().add(request.getMember()));
                meta = meta.withConfig(meta.getConfig().transitionTo(config));
                return stay(meta).handle(new ClientMessage(config, 0));
            }
        }

        @Override
        public State handle(RemoveServer request) {
            RaftMetadata meta = meta();
            if (meta.getConfig().isTransitioning()) {
                logger.warn("try remove server {} in transitioning state", request.getMember());
                send(request.getMember(), new RemoveServerResponse(
                    RemoveServerResponse.Status.TIMEOUT,
                    Option.some(clusterDiscovery.self())
                ));
            } else {
                StableClusterConfiguration config = new StableClusterConfiguration(
                    meta.membersWithout(request.getMember())
                );
                meta.getConfig().transitionTo(config);
                meta = meta.withConfig(meta.getConfig().transitionTo(config));
                return stay(meta).handle(new ClientMessage(config, 0));
            }
            return stay(meta);
        }

        @Override
        public State handle(RequestVote message) {
            if (message.getTerm() > meta().getCurrentTerm()) {
                if (logger.isInfoEnabled()) {
                    logger.info("received newer {}, current term is {}", message.getTerm(), meta().getCurrentTerm());
                }
                return stay(meta().withTerm(message.getTerm())).gotoFollower().handle(message);
            } else {
                logger.warn("rejecting vote for {}, and {}, currentTerm: {}, received stale term number {}",
                    message.getCandidate(), message.getTerm(),
                    meta().getCurrentTerm(), message.getTerm());
                send(message.getCandidate(), new DeclineCandidate(clusterDiscovery.self(), meta().getCurrentTerm()));
                return this;
            }
        }

        private void startHeartbeat() {
            if (logger.isInfoEnabled()) {
                logger.info("starting heartbeat");
            }
            context.startTimer(RaftContext.SEND_HEARTBEAT, heartbeat, heartbeat, () -> {
                lock.lock();
                try {
                    if (state == this) {
                        state = sendHeartbeat();
                    } else {
                        throw new IllegalStateException();
                    }
                } catch (IllegalStateException e) {
                    logger.error("error send heartbeat", e);
                } finally {
                    lock.unlock();
                }
            });
        }

        private LeaderState sendHeartbeat() {
            if (logger.isDebugEnabled()) {
                logger.debug("send heartbeat: {}", meta().members());
            }
            long timeout = System.currentTimeMillis() - heartbeat;
            for (DiscoveryNode member : meta().membersWithout(clusterDiscovery.self())) {
                // check heartbeat response timeout for prevent re-send heartbeat
                if (replicationIndex.getOrElse(member, 0L) < timeout) {
                    sendEntries(member);
                }
            }
            return this;
        }

        private void maybeSendEntries(DiscoveryNode follower) {
            // check heartbeat response timeout for prevent re-send heartbeat
            long timeout = System.currentTimeMillis() - heartbeat;
            if (replicationIndex.getOrElse(follower, 0L) < timeout) {
                // if member is already append prev entries,
                // their next index must be equal to last index in log
                if (nextIndex.indexFor(follower) <= replicatedLog.lastIndex()) {
                    sendEntries(follower);
                }
            }
        }

        private void sendEntries(DiscoveryNode follower) {
            RaftMetadata meta = meta();
            replicationIndex = replicationIndex.put(follower, System.currentTimeMillis());
            long lastIndex = nextIndex.indexFor(follower);

            if (replicatedLog.hasSnapshot()) {
                RaftSnapshot snapshot = replicatedLog.snapshot();
                if (snapshot.getMeta().getLastIncludedIndex() >= lastIndex) {
                    if (logger.isInfoEnabled()) {
                        logger.info("send install snapshot to {} in term {}", follower, meta.getCurrentTerm());
                    }
                    send(follower, new InstallSnapshot(clusterDiscovery.self(), meta.getCurrentTerm(), snapshot));
                    return;
                }
            }

            if (lastIndex > replicatedLog.nextIndex()) {
                throw new Error("Unexpected from index " + lastIndex + " > " + replicatedLog.nextIndex());
            } else {
                Vector<LogEntry> entries = replicatedLog.entriesBatchFrom(lastIndex, maxEntries);
                long prevIndex = Math.max(0, lastIndex - 1);
                long prevTerm = replicatedLog.termAt(prevIndex);
                if (logger.isInfoEnabled()) {
                    logger.info("send to {} append entries {} prev {}:{} in {} from index:{}", follower, entries.size(),
                        prevTerm, prevIndex, meta.getCurrentTerm(), lastIndex);
                }
                AppendEntries append = new AppendEntries(clusterDiscovery.self(), meta.getCurrentTerm(),
                    prevTerm, prevIndex,
                    entries,
                    replicatedLog.committedIndex());
                send(follower, append);
            }
        }

        private State maybeCommitEntry() {
            RaftMetadata meta = meta();
            long indexOnMajority;
            while ((indexOnMajority = matchIndex.consensusForIndex(meta.getConfig())) > replicatedLog.committedIndex()) {
                if (logger.isInfoEnabled()) {
                    logger.info("index of majority: {}", indexOnMajority);
                }
                Vector<LogEntry> entries = replicatedLog.slice(replicatedLog.committedIndex() + 1, indexOnMajority);
                // 3.6.2 To eliminate problems like the one in Figure 3.7, Raft never commits log entries
                // from previous terms by counting replicas. Only log entries from the leader’s current
                // term are committed by counting replicas; once an entry from the current term has been
                // committed in this way, then all prior entries are committed indirectly because of
                // the Log Matching Property.
                if (entries.get(entries.size() - 1).term() != meta.getCurrentTerm()) {
                    logger.warn("do not commit prev term");
                    return stay(meta);
                }
                for (LogEntry entry : entries) {
                    if (logger.isInfoEnabled()) {
                        logger.info("committing log at index: {}", entry.index());
                    }
                    replicatedLog.commit(entry.index());
                    if (entry.command() instanceof StableClusterConfiguration) {
                        StableClusterConfiguration config = (StableClusterConfiguration) entry.command();
                        if (logger.isInfoEnabled()) {
                            logger.info("apply new configuration, old: {}, new: {}", meta.getConfig(), config);
                        }
                        meta = meta.withConfig(config);
                        if (!meta.getConfig().containsOnNewState(clusterDiscovery.self())) {
                            return stay(meta).gotoFollower();
                        }
                    } else if (entry.command() instanceof Noop) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("ignore noop entry");
                        }
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.info("applying command[index={}]: {}",
                                entry.index(), entry.command().getClass().getSimpleName());
                        }
                        Streamable result = registry.apply(entry.index(), entry.command());
                        if (result != null) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("success client command session {}", entry.session());
                            }
                            Promise<Streamable> promise = sessionCommands.remove(entry.session());
                            if (promise != null) {
                                promise.success(result);
                            }
                        }
                    }
                }
            }
            if (replicatedLog.committedEntries() >= snapshotInterval) {
                return stay(meta).createSnapshot();
            } else {
                return stay(meta);
            }
        }
    }
}
