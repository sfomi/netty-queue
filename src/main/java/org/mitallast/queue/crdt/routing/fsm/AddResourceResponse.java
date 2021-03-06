package org.mitallast.queue.crdt.routing.fsm;

import org.mitallast.queue.common.stream.StreamInput;
import org.mitallast.queue.common.stream.StreamOutput;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.crdt.routing.ResourceType;

public class AddResourceResponse implements Streamable {
    private final ResourceType type;
    private final long id;
    private final boolean created;

    public AddResourceResponse(ResourceType type, long id, boolean created) {
        this.type = type;
        this.id = id;
        this.created = created;
    }

    public AddResourceResponse(StreamInput stream) {
        type = stream.readEnum(ResourceType.class);
        id = stream.readLong();
        created = stream.readBoolean();
    }

    @Override
    public void writeTo(StreamOutput stream) {
        stream.writeEnum(type);
        stream.writeLong(id);
        stream.writeBoolean(created);
    }

    public ResourceType type() {
        return type;
    }

    public long id() {
        return id;
    }

    public boolean isCreated() {
        return created;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AddResourceResponse that = (AddResourceResponse) o;

        if (id != that.id) return false;
        if (created != that.created) return false;
        return type == that.type;
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + (int) (id ^ (id >>> 32));
        result = 31 * result + (created ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "AddResourceResponse{" +
            "type=" + type +
            ", id=" + id +
            ", created=" + created +
            '}';
    }
}
