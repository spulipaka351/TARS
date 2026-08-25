package custom

import chisel3._
import chisel3.util._

class Softmax() extends BlackBox() with HasBlackBoxResource{
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
   
    val in_valid = Input(Bool())
    val row = Input(UInt(512.W))
    val out_valid = Output(Bool())
    val out = Output(UInt(512.W))
  })

override def desiredName: String = "Softmax"
addResource("Softmax/Softmax.sv")



}

class SoftmaxWrapper()extends Module{


val io = IO(new Bundle {

    val in_valid = Input(Bool())
    val row = Input(UInt(512.W))
    val out_valid = Output(Bool())
    val out = Output(UInt(512.W))
  })

  val softmax = Module(new Softmax())
    softmax.io.row :=io.row
    softmax.io.in_valid :=io.in_valid
    softmax.io.clk:=clock
    softmax.io.rst:=reset.asBool

    io.out_valid := softmax.io.out_valid
    io.out:=softmax.io.out
    
}