package org.mitallast.queue.common.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.util.UUID;

public class ByteBufStreamInput extends ByteBufInputStream implements StreamInput {

    private final ByteBuf buffer;

    public ByteBufStreamInput(ByteBuf buffer) {
        this(buffer, buffer.readableBytes());
    }

    public ByteBufStreamInput(ByteBuf buffer, int length) {
        super(buffer, length);
        this.buffer = buffer;
    }

    @Override
    public String readText() throws IOException {
        return readUTF();
    }

    @Override
    public String readTextOrNull() throws IOException {
        if (readBoolean()) {
            return readUTF();
        } else {
            return null;
        }
    }

    @Override
    public UUID readUUID() throws IOException {
        long most = readLong();
        long least = readLong();
        return new UUID(most, least);
    }

    @Override
    public UUID readUUIDOrNull() throws IOException {
        long most = readLong();
        long least = readLong();
        if (most == 0 && least == 0) {
            return null;
        } else {
            return new UUID(most, least);
        }
    }

    @Override
    public <Type extends Enum<Type>> Type readEnum(Class<Type> enumClass) throws IOException {
        int ord = readInt();
        return enumClass.getEnumConstants()[ord];
    }

    @Override
    public <Type extends Enum<Type>> Type readEnumOrNull(Class<Type> enumClass) throws IOException {
        int ord = readInt();
        if (ord < 0) {
            return null;
        } else {
            return enumClass.getEnumConstants()[ord];
        }
    }

    @Override
    public ByteBuf readByteBuf() throws IOException {
        int size = buffer.readInt();
        if (size == 0) {
            return Unpooled.EMPTY_BUFFER;
        }
        return buffer.readSlice(size).retain();
    }

    @Override
    public ByteBuf readByteBufOrNull() throws IOException {
        int size = buffer.readInt();
        if (size <= 0) {
            return null;
        }
        return buffer.readSlice(size).retain();
    }
}