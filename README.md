# TARS — Attention Accelerator

TARS is a hardware attention accelerator designed to couple with **RocketChip** and accelerate the core computation of Transformer attention:

[
O = \operatorname{Softmax}\left(QK^T\right)V
]
The core of TARS is a **mixed-precision systolic array (SA)** supporting **INT8 and FP16** computation. The architecture was originally designed with support for **NF4-based inference and LoRA workloads** in mind, while providing a general datapath for attention computation.

TARS uses a pipelined datapath for computationally intensive components such as the systolic array and softmax unit. A traditional FSM coordinates data movement, matrix multiplication, softmax, and scratchpad accesses.

---

## Architecture

The high-level TARS attention datapath is:

```text
                    ┌──────────────────────┐
                    │      RocketChip      │
                    │         Core         │
                    └──────────┬───────────┘
                               │
                               │ Control / Data
                               ▼
                    ┌──────────────────────┐
                    │      TARS Control     │
                    │        FSM            │
                    └──────────┬───────────┘
                               │
             ┌─────────────────┼─────────────────┐
             │                 │                 │
             ▼                 ▼                 ▼
        ┌─────────┐      ┌──────────┐      ┌─────────┐
        │    Q    │      │    K     │      │    V    │
        └────┬────┘      └────┬─────┘      └────┬────┘
             │                │                  │
             └────────┬───────┘                  │
                      ▼                          │
               ┌─────────────┐                   │
               │  Scratchpad │◄──────────────────┘
               └──────┬──────┘
                      │
                ┌─────┴─────┐
                │    AGUs   │
                └─────┬─────┘
                      │
                      ▼
             ┌─────────────────┐
             │ Mixed Precision │
             │ Systolic Array  │
             │   INT8 / FP16   │
             └────────┬────────┘
                      │
                      ▼
                   Q × Kᵀ
                      │
                      ▼
                ┌───────────┐
                │  Softmax  │
                └─────┬─────┘
                      │
                      ▼
                  S scratchpad
                      │
                      ▼
             ┌─────────────────┐
             │ Mixed Precision │
             │ Systolic Array  │
             └────────┬────────┘
                      │
                      ▼
                     S × V
                      │
                      ▼
                      O
```

The accelerator is implemented around a **32 × 32 systolic array**. The current softmax interface is fixed to a 512-bit row consisting of 32 FP16 values.

---

## Attention Computation

TARS implements attention in two matrix-multiplication phases.

### Phase 1 — QK

The first systolic-array operation computes:

[
A = QK^T
]

where (Q) and (K) are stored in the scratchpad.

The AGUs generate addresses required to feed the systolic array. Both operands are accessed in the required orientation for the (QK^T) operation.

The output of the systolic array is retained as the attention-score matrix.

---

### Phase 2 — Softmax

The resulting attention scores are processed row-by-row:

[
S_i = \operatorname{Softmax}(A_i)
]

The softmax module consumes one 32-element FP16 row at a time.

The normalized result is written back into the scratchpad in a dedicated **S region**.

This scratchpad writeback is intentional.

Softmax naturally produces data in **row-major order**, while the subsequent (S \times V) computation requires accessing columns of (S). Rather than introducing a dedicated hardware transpose network, TARS uses the scratchpad and AGU address generation to perform the required logical transpose.

Thus:

```text
Softmax output
     │
     ▼
Row-major S
     │
     ▼
Scratchpad
     │
     │ AGU transpose mode
     ▼
Column access during S × V
```

The scratchpad effectively acts as a **storage-based transpose mechanism**.

---

### Phase 3 — SV

The second systolic-array operation computes:

[
O = SV
]

where:

* (S = \operatorname{Softmax}(QK^T))
* (V) is the value matrix.

For this phase:

* S is accessed in transpose/column mode.
* V is accessed in normal/row-major mode.

For every inner-dimension step (t):

[
O_{ij} = \sum_t S_{it}V_{tj}
]

The resulting output is exposed through `out_sum`.

---

# Main Components

## 1. Mixed-Precision Systolic Array

The systolic array is the primary compute engine of TARS.

Current configuration:

```text
Array dimensions : 32 × 32
Data width       : 16 bits
Supported modes  : INT8 / FP16
```

The array is designed around pipelined processing elements, allowing multiple multiply-accumulate operations to be active simultaneously.

The `sa_mode` signal selects the active numerical mode:

```text
sa_mode = 0 → INT8
sa_mode = 1 → FP16
```

The same array can therefore be reused for different precision requirements without instantiating separate compute fabrics.

The architecture was also designed with future support for **NF4 dequantization / mixed-precision inference** and LoRA-oriented workloads.

---

## 2. Scratchpad

TARS uses a multi-port scratchpad for on-chip storage.

The current attention accelerator divides the scratchpad into four logical regions:

```text
┌─────────────────────────────────────┐
│ Q region                             │
├─────────────────────────────────────┤
│ K region                             │
├─────────────────────────────────────┤
│ V region                             │
├─────────────────────────────────────┤
│ S region                             │
└─────────────────────────────────────┘
```

The regions are:

* **Q** — Query matrix
* **K** — Key matrix
* **V** — Value matrix
* **S** — Softmax output

For a scratchpad of depth `depth`:

[
\text{regionWords} = \frac{\text{depth}}{4}
]

Each region must be aligned to the 32-lane datapath.

---

## 3. Address Generation Units

TARS uses two AGUs:

```text
AGU A
  Q during QK
  S during SV

AGU B
  K during QK
  V during SV
```

The AGUs generate the addresses required to stream matrix rows or columns into the systolic array.

### QK phase

```text
AGU A → Q, transpose access
AGU B → K, transpose access
```

### SV phase

```text
AGU A → S, transpose access
AGU B → V, normal access
```

This allows the same AGUs to be reused between both matrix multiplications.

---

# Control FSM

The accelerator is controlled by a traditional finite-state machine.

The current states are:

```text
sLoad
  ↓
sQK
  ↓
sQKDrain
  ↓
sSoftmax
  ↓
sClear
  ↓
sSV
  ↓
sSVDrain
  ↓
sDone
```

## `sLoad`

Loads Q, K, and V into their respective scratchpad regions.

The loading sequence is:

```text
Q → K → V
```

The FSM maintains:

* `phase`
* `loadCount`

to determine the current matrix and write position.

---

## `sQK`

The first matrix multiplication is performed:

[
QK^T
]

Both AGUs are enabled and the scratchpad supplies the row/column streams to the systolic array.

---

## `sQKDrain`

The AGUs have completed their final input transfer, but the systolic array still contains data propagating through its pipeline.

The drain state allows the pipeline to flush before the output is consumed.

The default drain duration is:

[
\text{drainCycles} = 32 + 32 + 8 = 72
]

unless explicitly overridden through the `drainCycles` parameter.

---

## `sSoftmax`

The QK result is consumed row-by-row by the softmax unit.

The 32 FP16 values from each systolic-array output row are packed into the 512-bit softmax input.

Softmax output is then written into the S region of the scratchpad.

---

## `sClear`

The systolic-array accumulators must be cleared before the second matrix multiplication.

This state asserts:

```text
sa.io.res := true.B
```

Without this operation, the second matrix multiplication could accumulate on top of the previous QK result.

Conceptually:

```text
Before sClear:

Accumulator = Q × Kᵀ

After sClear:

Accumulator = 0

Then:

Accumulator = S × V
```

---

## `sSV`

The second matrix multiplication is performed:

[
SV
]

The AGU configuration changes automatically:

```text
AGU A → S transpose
AGU B → V normal
```

The same systolic array is reused for this computation.

---

## `sSVDrain`

The second systolic-array pipeline is flushed.

Once the final result reaches the output registers, the accelerator transitions to `sDone`.

---

## `sDone`

The final attention output is available through:

```text
io.out_sum
```

and:

```text
io.valid
```

is asserted.

The accelerator remains parked in this state until `io.res` is asserted.

---

# Data Movement

The complete data movement is:

```text
Host / DMA
    │
    ├──────── Q ────────┐
    ├──────── K ────────┤
    └──────── V ────────┤
                        ▼
                 ┌─────────────┐
                 │ Scratchpad  │
                 └──────┬──────┘
                        │
                 ┌──────┴──────┐
                 │    AGUs     │
                 └──────┬──────┘
                        │
                        ▼
                 ┌─────────────┐
                 │  32 × 32 SA │
                 └──────┬──────┘
                        │
                        ▼
                      QKᵀ
                        │
                        ▼
                    Softmax
                        │
                        ▼
                 ┌─────────────┐
                 │ S Scratchpad│
                 └──────┬──────┘
                        │
                   AGU transpose
                        │
                        ▼
                 ┌─────────────┐
                 │  32 × 32 SA │
                 └──────┬──────┘
                        │
                        ▼
                       SV
                        │
                        ▼
                        O
```

The architecture deliberately reuses the same compute array for both GEMM operations, reducing hardware duplication.

---

# Interface

The top-level accelerator exposes:

| Signal      | Description                      |
| ----------- | -------------------------------- |
| `res`       | Accelerator reset                |
| `en`        | Enables input loading            |
| `d`         | Data/configuration input         |
| `sa_mode`   | Selects INT8/FP16 mode           |
| `data_in`   | 32-lane input data               |
| `Q_base`    | Base address of Q                |
| `K_base`    | Base address of K                |
| `V_base`    | Base address of V                |
| `S_base`    | Base address of S                |
| `out_sum`   | Attention output                 |
| `valid`     | Output is valid                  |
| `done`      | Final computation completed      |
| `ready`     | Accelerator is ready for loading |
| `dbg_state` | Current FSM state                |

---

# Parameterization

The accelerator is configurable through the constructor parameters:

```scala
ACCEL_ATTN(
  depth: Int = 4096,
  dataWidth: Int = 16,
  busWidth: Int = 32,
  N: Int = 32,
  K: Int = 32,
  drainCycles: Int = 0
)
```

Current architectural constraints are:

```text
busWidth  = 32
dataWidth = 16
```

These restrictions exist because the current `SoftmaxWrapper` has a fixed:

```text
512-bit input
32 × FP16 lanes
```

Therefore, while parts of the accelerator are parameterized, the complete attention datapath is currently specialized for a 32-element attention row.

---

# Pipeline Design

TARS uses pipelining in the major compute stages:

```text
Input
  │
  ▼
AGU
  │
  ▼
Scratchpad
  │
  ▼
Systolic Array
  │
  ▼
Softmax
  │
  ▼
Scratchpad
  │
  ▼
Systolic Array
  │
  ▼
Output
```

The goal is to maximize throughput by allowing the datapath to operate on multiple elements concurrently rather than performing attention operations sequentially in software.

The systolic array provides spatial parallelism, while pipelining allows different stages of the computation to remain active concurrently.

---

# RocketChip Integration

TARS is intended to be coupled with a **RocketChip RISC-V core**.

The accelerator can be exposed to software through a custom accelerator interface such as **RoCC**, with the Rocket core responsible for:

* configuring the accelerator,
* providing matrix base addresses,
* initiating computation,
* monitoring completion,
* and consuming the resulting output.

A future integrated system can therefore look like:

```text
                 RISC-V Rocket Core
                         │
                    RoCC Interface
                         │
                         ▼
                  ┌─────────────┐
                  │     TARS    │
                  │   Control   │
                  └──────┬──────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
      Scratchpad        AGUs           SA
          │                             │
          │                             ▼
          │                          Softmax
          │                             │
          └─────────────────────────────┘
```

The memory subsystem can subsequently be extended with DMA support so that Q/K/V tiles are transferred between the system memory hierarchy and the TARS scratchpad without requiring the CPU to explicitly move every element.

---

# Design Goals

The primary design goals of TARS are:

1. **High-throughput attention computation**
2. **Mixed-precision support**
3. **Reusable systolic-array datapath**
4. **On-chip scratchpad-based data reuse**
5. **Hardware address generation**
6. **Pipelined softmax computation**
7. **Compatibility with RocketChip**
8. **Future support for quantized LLM inference**
9. **Potential support for NF4 and LoRA workloads**

---

# Repository Structure

A typical repository structure is:

```text
TARS/
├── src/
│   └── main/
│       └── scala/
│           └── Tars/
│               ├── ACCEL_ATTN.scala
│               ├── AGU.scala
│               ├── ScratchpadMP.scala
│               └── ...
│
├── src/
│   └── test/
│       └── scala/
│           └── Tars/
│               └── ACCEL_ATTN_GOLDEN.scala
│
├── custom/
│   └── SoftmaxWrapper.*
│
├── SA/
│   └── Top.*
│
├── gen.py
├── requirements.txt
├── build.sbt
└── README.md
```

The exact source organization may differ depending on the current project checkout.

---

# Setup

## 1. Build the Chisel project

Install the required Scala/SBT environment and build the project:

```bash
sbt build
```

If the project is configured around the standard SBT test/build flow, the test target can also be invoked directly:

```bash
sbt test
```

---

## 2. Create the Python environment

Create a virtual environment:

```bash
python -m venv venv
```

Activate it.

### Linux/macOS

```bash
source venv/bin/activate
```

### Windows

```powershell
venv\Scripts\activate
```

Install Python dependencies:

```bash
pip install -r requirements.txt
```

---

# Generate Golden Data

The Python generator creates the Q/K/V input tensors and the expected attention output used for verification.

Run:

```bash
python gen.py
```

Conceptually, the golden model performs:

```python
scores = Q @ K.T
S = softmax(scores)
O = S @ V
```

The generated data is then consumed by the Chisel testbench.

---

# Run the Attention Accelerator Test

Run the golden test with:

```bash
sbt "testOnly Tars.ACCEL_ATTN_GOLDEN"
```

The testbench verifies the hardware result against the Python-generated golden output.

The intended verification flow is:

```text
             Python Golden Model
                     │
                  gen.py
                     │
                     ▼
              Q / K / V / O
                     │
                     │
                     ▼
          Chisel / ChiselTest TB
                     │
                     ▼
                ACCEL_ATTN
                     │
                     ▼
               Hardware O
                     │
                     ▼
              Compare with O
```

---

# Verification

The golden test validates the complete attention pipeline:

```text
Q
│
├──► Scratchpad
│
K ──► Scratchpad
│
V ──► Scratchpad
│
▼
Q × Kᵀ
│
▼
Softmax
│
▼
S
│
▼
S × V
│
▼
O
│
└──────► Compare against golden output
```

The FSM debug signal `dbg_state` is provided specifically to make simulation failures easier to diagnose.

For example:

```text
0 → sLoad
1 → sQK
2 → sQKDrain
3 → sSoftmax
4 → sClear
5 → sSV
6 → sSVDrain
7 → sDone
```

---

# Current Limitations

The current implementation has several intentional limitations:

### Fixed 32-lane softmax

The softmax interface is hardwired to:

```text
32 × FP16 = 512 bits
```

Therefore the complete attention pipeline currently targets a 32-element row.

### Fixed 32 × 32 systolic array

The current top-level configuration uses:

```text
32 × 32
```

Although some modules are parameterized, the surrounding datapath assumes this array size.

### Scratchpad-based transpose

The architecture currently relies on writing S to the scratchpad and reading it back using transpose-mode AGU addressing.

This avoids a dedicated transpose network at the cost of additional scratchpad traffic.

### Explicit pipeline drain

The FSM currently uses explicit drain states after each systolic-array computation.

This simplifies control and verification but can be optimized in a future implementation by exposing more precise pipeline-valid information from the SA.

---

# Future Work

* write handshake protocols btw module i.e(DecoupledIO).
* make outsum serial write back instead of 32x32x16 writeback.
* INT4/INT8/FP16 mixed-precision execution
* KV-cache management and compression
* Query-aware KV-cache retrieval
* Hardware/software co-designed long-context attention
* FPGA prototyping and ASIC synthesis
* Area, timing, and power characterization

---

# Project Status

TARS currently implements the core hardware attention datapath:

```text
Q/K/V Load
    ↓
Q × Kᵀ
    ↓
Softmax
    ↓
Scratchpad S
    ↓
S × V
    ↓
Output
```

The implementation combines a **mixed-precision systolic array**, **multi-port scratchpad**, **programmable address generation**, **pipelined softmax**, and an **FSM-based control path** into a single attention accelerator.

The immediate next architectural step is integration with RocketChip through a RoCC-style interface and a DMA/memory subsystem, enabling TARS to operate as a hardware accelerator rather than only as a standalone Chisel simulation model.

---

## Quick Start

```bash
# Build
sbt build

# Python environment
python -m venv venv
source venv/bin/activate

# Dependencies
pip install -r requirements.txt

# Generate golden Q/K/V/O
python gen.py

# Run hardware golden test
sbt "testOnly Tars.ACCEL_ATTN_GOLDEN"
```

**TARS = T**ransformer **A**ttention **R**ISC-V **S**ystolic accelerator.
