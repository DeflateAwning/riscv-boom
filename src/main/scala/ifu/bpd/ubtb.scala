package boom.ifu

import chisel3._
import chisel3.util._
import chisel3.experimental.dontTouch

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

import boom.common._
import boom.util.{BoomCoreStringPrefix}

case class BoomMicroBTBParams(
  nSets: Int = 256,
  offsetSz: Int = 13
)


class MicroBTBBranchPredictorBank(params: BoomMicroBTBParams)(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override val nSets         = params.nSets
  val tagSz         = vaddrBitsExtended - log2Ceil(nSets) - log2Ceil(fetchWidth) - 1
  val offsetSz      = params.offsetSz
  val bimParams     = BoomBIMParams(nSets = nSets, micro = true)

  require(isPow2(nSets))
  require(nSets <= bimParams.nSets)

  class MicroBTBEntry extends Bundle {
    val offset   = SInt(offsetSz.W)
  }
  val btbEntrySz = offsetSz

  class MicroBTBMeta extends Bundle {
    val is_br = Bool()
    val tag   = UInt(tagSz.W)
  }
  val btbMetaSz = tagSz + 1




  val bim = Module(new BIMBranchPredictorBank(bimParams))
  bim.io.f1_kill := io.f1_kill
  bim.io.f2_kill := io.f2_kill
  bim.io.f3_kill := io.f3_kill

  bim.io.f0_req := io.f0_req
  bim.io.update := io.update
  io.f1_resp := bim.io.f1_resp
  io.f2_resp := bim.io.f2_resp
  io.f3_resp := bim.io.f3_resp
  io.f3_meta := bim.io.f3_meta

  override val metaSz = bim.metaSz

  val doing_reset = RegInit(true.B)
  val reset_idx   = RegInit(0.U(log2Ceil(nSets).W))
  reset_idx := reset_idx + doing_reset
  when (reset_idx === (nSets-1).U) { doing_reset := false.B }

  val meta     = SyncReadMem(nSets, Vec(bankWidth, UInt(btbMetaSz.W)))
  val btb      = SyncReadMem(nSets, Vec(bankWidth, UInt(btbEntrySz.W)))

  val s1_req_rbtb  = VecInit(btb.read(s0_req_idx , io.f0_req.valid).map(_.asTypeOf(new MicroBTBEntry)))
  val s1_req_rmeta = VecInit(meta.read(s0_req_idx, io.f0_req.valid).map(_.asTypeOf(new MicroBTBMeta)))
  val s1_req_tag   = s1_req_idx >> log2Ceil(nSets)


  val s1_resp   = Wire(Vec(bankWidth, Valid(UInt(vaddrBitsExtended.W))))
  val s1_is_br  = Wire(Vec(bankWidth, Bool()))
  val s1_is_jal = Wire(Vec(bankWidth, Bool()))

  for (w <- 0 until bankWidth) {
    s1_resp(w).valid := !doing_reset && s1_req.valid && s1_req_tag(tagSz-1,0) === s1_req_rmeta(w).tag
    s1_resp(w).bits  := (s1_req.bits.pc.asSInt + (w << 1).S + s1_req_rbtb(w).offset).asUInt
    s1_is_br(w)  := !doing_reset && s1_resp(w).valid &&  s1_req_rmeta(w).is_br
    s1_is_jal(w) := !doing_reset && s1_resp(w).valid && !s1_req_rmeta(w).is_br
  }

  for (w <- 0 until bankWidth) {
    io.f1_resp(w).predicted_pc := s1_resp(w)
    io.f1_resp(w).is_br        := s1_is_br(w)
    io.f1_resp(w).is_jal       := s1_is_jal(w)
    when (s1_is_jal(w)) {
      io.f1_resp(w).taken := true.B
    }

    io.f2_resp(w).predicted_pc := RegNext(s1_resp(w))
    io.f2_resp(w).is_br        := RegNext(s1_is_br(w))
    io.f2_resp(w).is_jal       := RegNext(s1_is_jal(w))
    when (RegNext(s1_is_jal(w))) {
      io.f2_resp(w).taken := true.B
    }

    io.f3_resp(w).predicted_pc := RegNext(RegNext(s1_resp(w)))
    io.f3_resp(w).is_br        := RegNext(RegNext(s1_is_br(w)))
    io.f3_resp(w).is_jal       := RegNext(RegNext(s1_is_jal(w)))
    when (RegNext(RegNext(s1_is_jal(w)))) {
      io.f3_resp(w).taken := true.B
    }
  }

  val s1_update_cfi_idx = s1_update.bits.cfi_idx.bits

  val max_offset_value = (~(0.U)((offsetSz-1).W)).asSInt
  val min_offset_value = Cat(1.B, (0.U)((offsetSz-1).W)).asSInt
  val new_offset_value = (s1_update.bits.target.asSInt -
    (s1_update.bits.pc + (s1_update.bits.cfi_idx.bits << 1)).asSInt)

  val s1_update_wbtb_data     = Wire(new MicroBTBEntry)
  s1_update_wbtb_data.offset := new_offset_value
  val s1_update_wbtb_mask = (UIntToOH(s1_update_cfi_idx) &
    Fill(bankWidth, s1_update.bits.cfi_idx.valid && s1_update.valid && s1_update.bits.cfi_taken))

  val s1_update_wmeta_mask = ((s1_update_wbtb_mask | s1_update.bits.br_mask) &
    Fill(bankWidth, s1_update.valid))
  val s1_update_wmeta_data = Wire(Vec(bankWidth, new MicroBTBMeta))
  for (w <- 0 until bankWidth) {
    s1_update_wmeta_data(w).tag     := s1_update_idx >> log2Ceil(nSets)
    s1_update_wmeta_data(w).is_br   := s1_update.bits.br_mask(w)
  }

  btb.write(
    Mux(doing_reset,
      reset_idx,
      s1_update_idx),
    Mux(doing_reset,
      VecInit(Seq.fill(bankWidth) { 0.U(btbEntrySz.W) }),
      VecInit(Seq.fill(bankWidth) { s1_update_wbtb_data.asUInt })),
    Mux(doing_reset,
      (~(0.U(bankWidth.W))),
      s1_update_wbtb_mask).asBools
  )
  meta.write(
    Mux(doing_reset,
      reset_idx,
      s1_update_idx),
    Mux(doing_reset,
      VecInit(Seq.fill(bankWidth) { 0.U(btbMetaSz.W) }),
      VecInit(s1_update_wmeta_data.map(_.asUInt))),
    Mux(doing_reset,
      (~(0.U(bankWidth.W))),
      s1_update_wmeta_mask).asBools
  )
}
