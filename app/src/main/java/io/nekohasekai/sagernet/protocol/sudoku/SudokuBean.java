package io.nekohasekai.sagernet.fmt.sudoku;

import androidx.annotation.NonNull;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import org.jetbrains.annotations.NotNull;

/** Optional protocol/dial fields live in AbstractBean's versioned extension.
 * Their JSON presence is retained independently of the node JSON takeover mode. */
public class SudokuBean extends AbstractBean {
    // Upstream short-link local mixed port. Kept for sharing only; it must never
    // replace SakuraBox's application-wide listener or enter outbound options.
    public Integer shareLinkLocalPort;
    public static final Creator<SudokuBean> CREATOR = new CREATOR<SudokuBean>() {
        @NonNull public SudokuBean newInstance() { return new SudokuBean(); }
        @Override public SudokuBean[] newArray(int size) { return new SudokuBean[size]; }
    };

    @Override public void serialize(ByteBufferOutput output) {
        output.writeInt(shareLinkLocalPort == null ? 0 : 1);
        super.serialize(output);
        if (shareLinkLocalPort != null) output.writeInt(shareLinkLocalPort);
    }
    @Override public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        shareLinkLocalPort = version >= 1 ? input.readInt() : null;
    }
    @NotNull @Override public SudokuBean clone() {
        return KryoConverters.deserialize(new SudokuBean(), KryoConverters.serialize(this));
    }
}
