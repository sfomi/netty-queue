package org.mitallast.queue.action;

import org.mitallast.queue.common.stream.Streamable;

public abstract class ActionRequest implements Streamable {

    public abstract ActionType actionType();

    public abstract ActionRequestValidationException validate();
}
