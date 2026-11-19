package io.nekohasekai.sagernet.fmt.nakis;

import androidx.annotation.NonNull;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import org.jetbrains.annotations.NotNull;

public final class NakisBean extends AbstractBean {
    public static final Creator<NakisBean> CREATOR = new CREATOR<NakisBean>() {
        @NonNull @Override public NakisBean newInstance() { return new NakisBean(); }
        @Override public NakisBean[] newArray(int size) { return new NakisBean[size]; }
    };

    @Override public void initializeDefaultValues() {
        if (serverPort == null) serverPort = 8443;
        super.initializeDefaultValues();
    }

    @Override public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
    }

    @Override public void deserialize(ByteBufferInput input) {
        if (input.readInt() != 0) throw new IllegalArgumentException("Unsupported Nakis bean version");
        super.deserialize(input);
    }

    @NotNull @Override public NakisBean clone() {
        return KryoConverters.deserialize(new NakisBean(), KryoConverters.serialize(this));
    }
}
