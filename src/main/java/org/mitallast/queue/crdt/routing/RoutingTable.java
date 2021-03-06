package org.mitallast.queue.crdt.routing;

import javaslang.collection.HashSet;
import javaslang.collection.Set;
import javaslang.collection.Vector;
import org.mitallast.queue.common.stream.StreamInput;
import org.mitallast.queue.common.stream.StreamOutput;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.transport.DiscoveryNode;


public class RoutingTable implements Streamable {
    private final int replicas;
    private final Set<DiscoveryNode> members;
    private final Vector<RoutingBucket> buckets;
    private final long nextReplica;

    public RoutingTable(int replicas, int buckets) {
        this(replicas, HashSet.empty(), Vector.range(0, buckets).map(RoutingBucket::new), 0);
    }

    public RoutingTable(int replicas, Set<DiscoveryNode> members, Vector<RoutingBucket> buckets, long nextReplica) {
        this.replicas = replicas;
        this.members = members;
        this.buckets = buckets;
        this.nextReplica = nextReplica;
    }

    public RoutingTable(StreamInput stream) {
        replicas = stream.readInt();
        members = stream.readSet(DiscoveryNode::new);
        buckets = stream.readVector(RoutingBucket::new);
        nextReplica = stream.readLong();
    }

    @Override
    public void writeTo(StreamOutput stream) {
        stream.writeInt(replicas);
        stream.writeSet(members);
        stream.writeVector(buckets);
        stream.writeLong(nextReplica);
    }

    public int replicas() {
        return replicas;
    }

    public Set<DiscoveryNode> members() {
        return members;
    }

    public Vector<RoutingBucket> buckets() {
        return buckets;
    }

    public int bucketsCount(DiscoveryNode node) {
        return buckets.count(bucket -> bucket.replicas().values().exists(replica -> replica.member().equals(node)));
    }

    public RoutingBucket bucket(long resourceId) {
        int bucket = Long.hashCode(resourceId) % buckets.size();
        return buckets.get(bucket);
    }

    public Resource resource(long id) {
        return bucket(id).resource(id);
    }

    public boolean hasResource(long id) {
        return bucket(id).hasResource(id);
    }

    public RoutingTable withResource(Resource resource) {
        RoutingBucket bucket = bucket(resource.id()).withResource(resource);
        return new RoutingTable(
            replicas,
            members,
            buckets.update(bucket.index(), bucket),
            nextReplica
        );
    }

    public RoutingTable withoutResource(long id) {
        RoutingBucket bucket = bucket(id).withoutResource(id);
        return new RoutingTable(
            replicas,
            members,
            buckets.update(bucket.index(), bucket),
            nextReplica
        );
    }

    public RoutingTable withReplica(int bucket, DiscoveryNode member) {
        RoutingBucket updated = buckets.get(bucket).withReplica(new RoutingReplica(nextReplica, member));
        return new RoutingTable(
            replicas,
            members,
            buckets.update(bucket, updated),
            nextReplica + 1
        );
    }

    public RoutingTable withReplica(int bucket, RoutingReplica replica) {
        RoutingBucket updated = buckets.get(bucket).withReplica(replica);
        return new RoutingTable(
            replicas,
            members,
            buckets.update(bucket, updated),
            nextReplica
        );
    }

    public RoutingTable withMembers(Set<DiscoveryNode> members) {
        return new RoutingTable(
            replicas,
            members,
            buckets.map(bucket -> bucket.filterReplicas(members)),
            nextReplica
        );
    }

    public RoutingTable withoutReplica(int bucket, long replica) {
        RoutingBucket updated = buckets.get(bucket).withoutReplica(replica);
        return new RoutingTable(
            replicas,
            members,
            buckets.update(bucket, updated),
            nextReplica
        );
    }

    @Override
    public String toString() {
        return "RoutingTable{" +
            "replicas=" + replicas +
            ", members=" + members +
            ", buckets=" + buckets +
            ", nextReplica=" + nextReplica +
            '}';
    }
}
