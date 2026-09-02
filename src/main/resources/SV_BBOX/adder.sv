module Adder #(
    parameter int WIDTH = 4
) (
    input  logic [WIDTH-1:0] a,
    input  logic [WIDTH-1:0] b,
    input  logic             cin,
    output logic [WIDTH-1:0] sum,
    output logic             cout
);

    // carry[0] is the incoming carry, carry[WIDTH] is the outgoing carry
    logic [WIDTH:0] carry;
    assign carry[0] = cin;

    genvar i;
    generate
        for (i = 0; i < WIDTH; i++) begin : adder_loop
            FullAdder fa (
                .a        (a[i]),
                .b        (b[i]),
                .carry_in (carry[i]),
                .sum      (sum[i]),
                .carry    (carry[i+1])
            );
        end
    endgenerate

    assign cout = carry[WIDTH];

endmodule : Adder

module FullAdder (
    input  logic a,
    input  logic b,
    input  logic carry_in,
    output logic sum,
    output logic carry
);
    assign sum   = a ^ b ^ carry_in;
    assign carry = (a & b) | (carry_in & (a ^ b));
endmodule : FullAdder
