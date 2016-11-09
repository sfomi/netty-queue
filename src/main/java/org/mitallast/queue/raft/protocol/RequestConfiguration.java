package org.mitallast.queue.raft.protocol;

import org.mitallast.queue.common.stream.StreamInput;
import org.mitallast.queue.common.stream.StreamOutput;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.transport.DiscoveryNode;

import java.io.IOException;

public class RequestConfiguration implements Streamable {
    private final DiscoveryNode member;

    public RequestConfiguration(StreamInput stream) throws IOException {
        member = stream.readStreamable(DiscoveryNode::new);
    }

    public RequestConfiguration(DiscoveryNode member) {
        this.member = member;
    }

    public DiscoveryNode getMember() {
        return member;
    }

    @Override
    public void writeTo(StreamOutput stream) throws IOException {
        stream.writeStreamable(member);
    }
}
