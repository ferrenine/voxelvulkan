package voxel.common.world;

import static voxel.common.world.Mapper.withLight;

//Mipper for data
public class Mipper {
    //TODO: compute the opacity of the block then mip w.r.t those blocks
    // as distant horizons done


    //TODO: also pass in the level its mipping from, cause at lower levels you want to preserve block details
    // but at higher details you want more air



    //TODO: instead of opacity only, add a level to see if the visual bounding box allows for seeing through top down etc
    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                          Mapper mapper) {
        //TODO: do a stable sort on all the entires, w.r.t the opacity and maybe light as a secondary???
        // then select the highest value
        // UPDATE, dumbass, the highest value _is_ the max/min



        int max = -1;

        //AUDIT-FIXME: mip selection is opacity-weighted winner-take-all, not the
        // documented "most top corner" / averaged light — semantics open.

        //TODO: i think it needs to compute the _max_ light level, since e.g. if a point is bright irl
        // you can see it from really really damn far away.
        // it could be a heavily weighted average with a huge preference to the top most lighting value
        if (!Mapper.isAir(I111)) {
            max = (mapper.getBlockStateOpacity(I111)<<4)|0b111;
        }
        if (!Mapper.isAir(I110)) {
            max = Math.max((mapper.getBlockStateOpacity(I110)<<4)|0b110, max);
        }
        if (!Mapper.isAir(I011)) {
            max = Math.max((mapper.getBlockStateOpacity(I011)<<4)|0b011, max);
        }
        if (!Mapper.isAir(I010)) {
            max = Math.max((mapper.getBlockStateOpacity(I010)<<4)|0b010, max);
        }
        if (!Mapper.isAir(I101)) {
            max = Math.max((mapper.getBlockStateOpacity(I101)<<4)|0b101, max);
        }
        if (!Mapper.isAir(I100)) {
            max = Math.max((mapper.getBlockStateOpacity(I100)<<4)|0b100, max);
        }
        if (!Mapper.isAir(I001)) {
            max = Math.max((mapper.getBlockStateOpacity(I001)<<4)|0b001, max);
        }
        if (!Mapper.isAir(I000)) {
            max = Math.max((mapper.getBlockStateOpacity(I000)<<4), max);
        }

        if (max != -1) {
            long best = switch (max&0b111) {
                case 0 -> I000;
                case 1 -> I001;
                case 2 -> I010;
                case 3 -> I011;
                case 4 -> I100;
                case 5 -> I101;
                case 6 -> I110;
                case 7 -> I111;
                default -> throw new IllegalStateException("Unexpected value: " + (max&0b111));
            };
            // Keep the winning block/biome but average the light of the 8 cells.
            // Taking the winner's own light biased every merged cell towards the
            // +X/+Y/+Z corner (the tie-break above), so each mip level pulled
            // canopy interiors towards the brightest top corner and far trees
            // lost their internal shading. Cells with a zero light byte are
            // opaque blocks (the renderer uses the same convention in
            // RenderDataFactory.computeCornerLightAO) and are skipped.
            int blockSum = 0;
            int skySum = 0;
            int litCells = 0;
            long[] cells = {I000, I100, I001, I101, I010, I110, I011, I111};
            for (long cell : cells) {
                int light = Mapper.getLightId(cell);
                if (light == 0) {
                    continue;
                }
                blockSum += (light >> 4) & 0xF;
                skySum += light & 0xF;
                litCells++;
            }
            if (litCells == 0) {
                return best;
            }
            int blockLight = blockSum / litCells;
            int skyLight = (skySum + litCells - 1) / litCells;
            return withLight(best, (blockLight << 4) | skyLight);
        } else {
            int skySum = (Mapper.getLightId(I000) & 0xF) + (Mapper.getLightId(I001) & 0xF) + (Mapper.getLightId(I010) & 0xF) + (Mapper.getLightId(I011) & 0xF) +
                    (Mapper.getLightId(I100) & 0xF) + (Mapper.getLightId(I101) & 0xF) + (Mapper.getLightId(I110) & 0xF) + (Mapper.getLightId(I111) & 0xF);
            int blockSum = ((Mapper.getLightId(I000) >> 4) & 0xF) + ((Mapper.getLightId(I001) >> 4) & 0xF) + ((Mapper.getLightId(I010) >> 4) & 0xF) + ((Mapper.getLightId(I011) >> 4) & 0xF) +
                    ((Mapper.getLightId(I100) >> 4) & 0xF) + ((Mapper.getLightId(I101) >> 4) & 0xF) + ((Mapper.getLightId(I110) >> 4) & 0xF) + ((Mapper.getLightId(I111) >> 4) & 0xF);
            int skyLight = (skySum + 7) / 8;
            int blockLight = blockSum / 8;

            return withLight(I111, (blockLight << 4) | skyLight);
        }
    }
}
