# SakuraBox table construction policy

This client-local extension to the pinned Sudoku fork does not change wire data,
character tables, ordered rotation or duplicate weights. Saved configuration is
never silently normalized to fit a runtime budget.

## Node policy and configuration statistics

`table_limit` is an optional outbound JSON integer. Omitted/null means 32.
The native editor offers 32, 64, 128, 256, 512 and Unlimited (numeric 0).
For a finite N, the node permits N physical tables and N MiB of estimated
construction allocation. This scales the original F-03 32-table / 32-MiB policy;
it is not a reservation or a promise that a device has that memory available.
Unsupported integers fail core checking, without adding a node-save gate.

Every duplicate counts. Equal up/down tokens share one table; differing tokens
build two per rotation entry. A nonempty `custom_tables` wins over `custom_table`;
otherwise the nonblank single table or default contributes one entry. Original
ASCII parsing and defaults remain unchanged. Count checking precedes key hashing
and input traversal for over-limit finite lists. Count limits bound repeated
construction work: each physical table searches 256 * 1820 hint combinations.

Each outbound uses its own policy. Configuration checking first checks ALL node
counts, then computes key-specific estimates and sums counts/estimated bytes with
checked uint64 arithmetic. There is no shared 32-table / 32-MiB configuration cap:
otherwise independent 64--512 and Unlimited settings could not take effect.
An Unlimited outbound cannot disable another outbound's finite budget. Statistics
are available internally through `estimateSudokuResources`; no new logging of
keys or user configuration is introduced.

## Allocation estimate

Puzzles depend on the key-seeded 256 grids, not ASCII/custom byte layouts. The
preflight uses only hint-set/grid workspace to count P puzzles, without building
puzzle arrays. For physical-table count N and selected key/pattern input bytes S:

    N * (8 * 4 * max(P, 24576) + 65536 + 4 * S)

24,576 uint32 entries are the unchanged constructor's initial capacity. With the
pinned Go 1.26.9 slice-growth/allocator rules, 8x bounds all intermediate puzzle
backing allocations (including unreclaimed growth arrays). The 64-KiB allowance
covers the Table, hint sets, layout, RNG/grid workspace, allocator rounding and
small preflight objects; 4*S covers selected input copies. Inputs, additions and
multiplications are checked before large allocation. For usual P <= 24,576,
base allowance is 832 KiB per physical table, before strings. Long keys or unusual
puzzle counts can reach the byte budget before the count budget. No deduplication,
reordering, truncation or changes to rotation weights are performed.

`NewTableSet` is the final shared guard, before table-pointer lists and puzzle
arrays. The outbound constructor forwards its selected policy to that factory.
JNI construction and explicit `CheckConfig` run the same whole-config preflight.
The finite fast path can use the theoretical puzzle upper bound when it fits.

## Unlimited and process memory

Numeric 0 disables BOTH configurable count and estimated-byte quotas. Exact
preflight statistics still use checked uint64 arithmetic and platform int / pointer
array sizing, including 32-bit ABIs. The factory converts recoverable Go runtime
slice-allocation panics into `SUDOKU_TABLE_RESOURCE_LIMIT`; unexpected programming
panics are rethrown. Go fatal process/OS out-of-memory termination is not recoverable
by `recover`. Unlimited therefore cannot guarantee graceful failure on exhausted
devices; it intentionally gives up the F-03 memory/CPU work bound. Tests run in
resource-limited isolated processes and never construct the historical 4,000-entry
Unlimited fixture on a user VPN.

Finite quotas also do not cap total process memory: multiple outbounds, concurrent
core instances, parsing, buffers and other protocols consume additional memory.
Configuration statistics do not establish an OS-wide memory limit.

## Storage and sharing

The new field uses existing SudokuBean opaque JSON; no Kryo/Room version change.
Selecting the default 32 omits the field; untouched imported explicit values stay
as saved. Numeric 0 remains distinct from omission. The upstream `sudoku://` short
format cannot represent this local runtime policy. Nodes with an explicit policy
use the existing SN fallback instead of inventing URI fields or losing settings.

Ordinary UI storage remains independent of runtime construction. The existing JSON
storage helper accepts only the anchored Sudoku resource-limit error and skips
subsequent construction; other syntax/type/runtime checks retain their prior
behavior. Explicit checks and connection/URL Test still enforce the selected
policy. No new popup, warning, Toast or save interception is introduced.
