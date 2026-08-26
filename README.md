# TARS — Attention Accelerator

**T**ransformer **A**ttention **R**ISC-V **S**ystolic accelerator, built to couple with a RocketChip RISC-V core.

TARS accelerates `O = Softmax(QKᵀ/√d_k)V` using a shared **32×32 mixed-precision (INT8/FP16) systolic array**, a multi-port scratchpad, programmable AGUs, and an FSM controller. The `1/√d_k` scaling is applied inside the softmax unit itself, not as a separate step on the raw `QKᵀ` output. It was designed with an eye toward future NF4 dequantization and LoRA-style inference workloads.

## Architecture

```
Q/K/V → Scratchpad → AGUs → 32×32 Systolic Array → QKᵀ
                                                      │
                                                   Softmax
                                                      │
                                              S → Scratchpad (transpose)
                                                      │
                                    AGUs → 32×32 Systolic Array (reused) → O
```

The same systolic array computes both `QKᵀ` and `SV`; a **scratchpad-based transpose** (via AGU addressing) converts softmax's row-major output into the column access `SV` needs, avoiding a dedicated transpose network.

## Main Components

| Component | Notes |
|---|---|
| **Systolic Array** | 32×32, 16-bit datapath, `sa_mode` selects INT8 (0) / FP16 (1) |
| **Scratchpad** | 4 regions — Q, K, V, S — each aligned to the 32-lane datapath |
| **AGUs** | AGU A: Q (QK) / S-transpose (SV); AGU B: K-transpose (QK) / V-normal (SV) |
| **Softmax unit** | Fixed 512-bit input = 32 × FP16 lanes, processes one row at a time; applies `1/√d_k` scaling internally before normalization |

## Softmax Mechanism

The softmax unit computes a numerically stable softmax across a 32-element FP16 row in six pipelined stages:

1. **Row max** — `MaxTree` finds the max of the 32 inputs via a 5-level pairwise reduction (`FP16Max`).
2. **Max-subtraction** — each element is delayed to match the max-tree latency, then has the row max subtracted (`FP16AddSub`, `sub=1`), giving `x - max` for numerical stability.
3. **Scaled exponential** — `Exponential` computes `e^{(x-max)/√d_k}` directly: it multiplies `|x-max|` by a precomputed fixed-point constant `log2(e)/√d_k` (`SCALE_Q20`), splits the result into an integer part (binary exponent) and fractional part, and evaluates `2^frac` via a 33-entry LUT with linear interpolation — this is where the `1/√d_k` scaling actually gets folded in, rather than as a separate multiply.
4. **Row sum** — `AdderTree` sums the 32 exponentials (5-level `FP16AddSub` reduction) into the softmax denominator.
5. **Reciprocal** — `Reciprocal` computes `1/sum` via a linearly-interpolated 33-entry LUT (chosen over nearest-neighbor lookup so the row-sum bias stays below FP16's mantissa noise floor).
6. **Normalize** — each delayed exponential is multiplied (`FP16Multiplier`) by the shared reciprocal to produce the final softmax row.

`DelayLine` shift registers keep every parallel lane time-aligned with the slowest stage (the max/sum reduction trees), and `in_valid` is piped through the same depth so `out_valid` lines up with the output row.

## Control FSM

```
sLoad → sQK → sQKDrain → sSoftmax → sClear → sSV → sSVDrain → sDone
```
- **sLoad**: streams Q→K→V into scratchpad
- **sQK / sSV**: the two matrix multiplies (same SA reused)
- **Drain states**: flush the SA pipeline (default `drainCycles = 32+32+8 = 72`)
- **sClear**: resets SA accumulator between QK and SV so results don't sum together
- **sDone**: `out_sum`/`valid` asserted, holds until `res`

## Interface

Key signals: `res`, `en`, `d`, `sa_mode`, `data_in`, `Q_base`/`K_base`/`V_base`/`S_base`, `out_sum`, `valid`, `done`, `ready`, `dbg_state`.

## Parameters

```scala
ACCEL_ATTN(depth: Int = 4096, dataWidth: Int = 16, busWidth: Int = 32,
           N: Int = 32, K: Int = 32, drainCycles: Int = 0)
```
Current constraints: `busWidth = 32`, `dataWidth = 16` (fixed by the SoftmaxWrapper's 512-bit/32-lane interface) — the full pipeline currently targets a 32-element attention row.

## RocketChip Integration

Intended to attach via a **RoCC** interface, with Rocket handling configuration, base addresses, computation kickoff, and result consumption. A future DMA extension would move Q/K/V tiles without CPU-driven element transfers.

## Setup & Test

```bash
# Build (Chisel/SBT)
sbt build

# Python env for golden model
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
python gen.py                                    # generates Q/K/V/O golden data

# Run hardware vs. golden-model test
sbt "testOnly Tars.ACCEL_ATTN_GOLDEN"
```
Golden model: `S = softmax(Q@K.T)`, `O = S@V` — compared against hardware `out_sum`. `dbg_state` maps 0–7 to the FSM states above for debugging.

## Current Limitations

- Fixed 32-lane softmax and fixed 32×32 SA — datapath assumes this size even where modules are parameterized
- Scratchpad-based transpose trades extra scratchpad traffic for avoiding a transpose network
- Explicit drain states (simpler control/verification, but not pipeline-valid-optimal)

## Future Work

- DecoupledIO handshake protocols
- Serial `out_sum` writeback (vs. full 32×32×16 dump)
- INT4/INT8/FP16 mixed-precision execution
- KV-cache management, compression, and query-aware retrieval
- Long-context attention (HW/SW co-design)
- FPGA prototyping, ASIC synthesis, PPA characterization

## Repo Layout

```
TARS/
├── src/main/scala/Tars/{ACCEL_ATTN,AGU,ScratchpadMP}.scala
├── src/test/scala/Tars/ACCEL_ATTN_GOLDEN.scala
├── custom/SoftmaxWrapper.*
├── SA/Top.*
├── gen.py / requirements.txt / build.sbt
```
