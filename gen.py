
import math
import os
import numpy as np
import torch
import torch.nn.functional as F

D = 32
OUTDIR = "golden"
SEED = 0


def to_fp16_hex_lines(t16: torch.Tensor):
    """Raw FP16 bit patterns, row-major, as 4-digit hex strings."""
    bits = t16.contiguous().numpy().view(np.uint16).reshape(-1)
    return [f"{w:04X}" for w in bits]


def main():
    torch.manual_seed(SEED)
    os.makedirs(OUTDIR, exist_ok=True)

    q = torch.randn(D, D)
    k = torch.randn(D, D)
    v = torch.randn(D, D)

    # NO host-side 1/sqrt(d) scaling any more. The hardware folds it into
    # the Exponential module's SCALE_Q20 constant, so Q is loaded raw and
    # the scale is applied inside the softmax. Pre-scaling here as well
    # would apply it TWICE.
    #
    # The reference below therefore divides the scores, not Q -- which is
    # mathematically identical but keeps the loaded Q matching what the
    # accelerator actually receives.

    # Quantize to what the hardware will actually receive.
    q16, k16, v16 = q.half(), k.half(), v.half()

    # Golden computed from the QUANTIZED values, in float64.
    qd, kd, vd = q16.double(), k16.double(), v16.double()
    scores = (qd @ kd.T) / math.sqrt(D)
    s = F.softmax(scores, dim=1)
    o = s @ vd

    for name, t16 in (("q", q16), ("k", k16), ("v", v16)):
        with open(os.path.join(OUTDIR, f"{name}.hex"), "w") as f:
            f.write("\n".join(to_fp16_hex_lines(t16)) + "\n")

    for name, t in (("s_golden", s), ("o_golden", o)):
        with open(os.path.join(OUTDIR, f"{name}.txt"), "w") as f:
            f.write("\n".join(f"{x:.10e}" for x in t.reshape(-1).tolist()) + "\n")

    # Sanity stats -- worth eyeballing before trusting a run.
    print(f"scores  min={scores.min():+.4f}  max={scores.max():+.4f}  std={scores.std():.4f}")
    print(f"softmax row sums: min={s.sum(1).min():.6f} max={s.sum(1).max():.6f}")
    print(f"softmax max weight per row: mean={s.max(1).values.mean():.4f}")
    print(f"output  min={o.min():+.4f}  max={o.max():+.4f}")

    # FP16 range check -- if scores exceed ~±11 the exp() in a LUT-based
    # softmax may saturate, and FP16 itself overflows past 65504.
    if scores.abs().max() > 11:
        print("WARNING: |scores| > 11, softmax LUT may saturate")

    print(f"\nwrote {OUTDIR}/{{q,k,v}}.hex and {OUTDIR}/{{s,o}}_golden.txt")


if __name__ == "__main__":
    main()