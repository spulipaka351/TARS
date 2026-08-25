package custom

import chisel3._
import chisel3.util._

class BBOX() extends BlackBox() with HasBlackBoxResource{
  val io = IO(new Bundle {
    val a = Input(UInt(16.W))    // in_a delayed 3 cycles
    val b = Input(UInt(16.W)) 
    val clk = Input(Clock())
    val rst = Input(Bool())
    val mode = Input(Bool())
    val out = Output(UInt(16.W))
    val out_a = Output(UInt(16.W))
    val out_b = Output(UInt(16.W))
  })

override def desiredName: String = "PE"
addResource("Pipelined_FP_MUL/PE.sv")



}

class PEWrapper()extends Module{


val io = IO(new Bundle {
    val a = Input(UInt(16.W))    // in_a delayed 3 cycles
    val b = Input(UInt(16.W)) 
    val clk = Input(Clock())
    val rst = Input(Bool())
    val mode = Input(Bool())
    val out = Output(UInt(16.W))
    val out_a = Output(UInt(16.W))
    val out_b = Output(UInt(16.W))
  })

  val pe = Module(new BBOX())
    pe.io.a := io.a
    pe.io.b := io.b
    pe.io.clk:=io.clk
    pe.io.rst:=io.rst
    pe.io.mode:=io.mode
    io.out := pe.io.out
    io.out_a:=pe.io.out_a
    io.out_b:=pe.io.out_b
    
}