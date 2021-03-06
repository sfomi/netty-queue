package org.mitallast.queue.common.events;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

public interface EventBus {

    <Event> void subscribe(Class<Event> eventClass, Consumer<Event> consumer);

    <Event> void subscribe(Class<Event> eventClass, Consumer<Event> consumer, Executor executor);

    <Event> void unsubscribe(Class<Event> eventClass, Consumer<Event> consumer);

    <Event> void trigger(Event event);

    <Event> void trigger(Class<Event> eventClass, Event event);
}
