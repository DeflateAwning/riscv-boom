
import "DPI-C" function void initialize_btb();



import "DPI-C" function void predict_target(input longint ip,
                                            output bit     valid,
                                            output longint target,
                                            output bit     is_br,
                                            output bit     is_jal
                                            );

import "DPI-C" function void update_btb(input longint ip,
                                        input longint target,
                                        input bit     is_br,
                                        input bit     is_jal
                                        );

module BTBHarness (input clock,
                   input         reset,

                   input         req_valid,
                   input [63:0]  req_pc,
                   output        req_target_valid,
                   output [63:0] req_target_pc,
                   output        req_is_br,
                   output        req_is_jal,

                   input         update_valid,
                   input [63:0]  update_pc,
                   input [63:0]  update_target,
                   input         update_is_br,
                   input         update_is_jal
                   );

   initial begin
      initialize_btb();
   end

   bit _req_target_valid;
   longint _req_target_pc;
   bit     _req_is_br;
   bit     _req_is_jal;

   assign req_target_valid = _req_target_valid;
   assign req_target_pc = _req_target_pc;
   assign req_is_br = _req_is_br;
   assign req_is_jal = _req_is_jal;


   always @(posedge clock) begin
      if (!reset) begin
         if (req_valid) begin
            predict_target(req_pc, _req_target_valid, _req_target_pc, _req_is_br, _req_is_jal);
         end
         if (update_valid) begin
            update_btb(update_pc, update_target, update_is_br, update_is_jal);
         end
      end
   end

endmodule