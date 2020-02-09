package mindustry.world.blocks;

import arc.*;
import arc.Graphics.*;
import arc.Graphics.Cursor.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.util.ArcAnnotate.*;
import arc.util.*;
import mindustry.annotations.Annotations.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.modules.*;

import java.io.*;

import static mindustry.Vars.*;

public class BuildBlock extends Block{
    public static final int maxSize = 9;
    private static final BuildBlock[] buildBlocks = new BuildBlock[maxSize];

    private static long lastTime = 0;
    private static int pitchSeq = 0;
    private static long lastPlayed;

    public BuildBlock(int size){
        super("build" + size);
        this.size = size;
        update = true;
        health = 20;
        layer = Layer.placement;
        consumesTap = true;
        solidifes = true;
        entityType = BuildEntity::new;

        buildBlocks[size - 1] = this;
    }

    /** Returns a BuildBlock by size. */
    public static BuildBlock get(int size){
        if(size > maxSize) throw new IllegalArgumentException("No. Don't place BuildBlocks of size greater than " + maxSize);
        return buildBlocks[size - 1];
    }

    @Remote(called = Loc.server)
    public static void onDeconstructFinish(Tile tile, Block block, int builderID){
        Team team = tile.team();
        Fx.breakBlock.at(tile.drawx(), tile.drawy(), block.size);
        Events.fire(new BlockBuildEndEvent(tile, Groups.player.getByID(builderID), team, true));
        tile.remove();
        if(shouldPlay()) Sounds.breaks.at(tile, calcPitch(false));
    }

    @Remote(called = Loc.server)
    public static void onConstructFinish(Tile tile, Block block, int builderID, byte rotation, Team team, boolean skipConfig){
        if(tile == null) return;
        float healthf = tile.entity == null ? 1f : tile.entity.healthf();
        tile.set(block, team, rotation);
        if(tile.entity != null){
            tile.entity.health(block.health * healthf);
        }
        //last builder was this local client player, call placed()
        if(!headless && builderID == player.id()){
            if(!skipConfig){
                tile.block().playerPlaced(tile);
            }
        }
        Fx.placeBlock.at(tile.drawx(), tile.drawy(), block.size);
    }

    static boolean shouldPlay(){
        if(Time.timeSinceMillis(lastPlayed) >= 32){
            lastPlayed = Time.millis();
            return true;
        }else{
            return false;
        }
    }

    static float calcPitch(boolean up){
        if(Time.timeSinceMillis(lastTime) < 16 * 30){
            lastTime = Time.millis();
            pitchSeq ++;
            if(pitchSeq > 30){
                pitchSeq = 0;
            }
            return 1f + Mathf.clamp(pitchSeq / 30f) * (up ? 1.9f : -0.4f);
        }else{
            pitchSeq = 0;
            lastTime = Time.millis();
            return Mathf.random(0.7f, 1.3f);
        }
    }

    public static void constructed(Tile tile, Block block, int builderID, byte rotation, Team team, boolean skipConfig){
        Call.onConstructFinish(tile, block, builderID, rotation, team, skipConfig);
        tile.block().placed(tile);

        Events.fire(new BlockBuildEndEvent(tile, Groups.player.getByID(builderID), team, false));
        if(shouldPlay()) Sounds.place.at(tile, calcPitch(true));
    }

    @Override
    public boolean isHidden(){
        return true;
    }

    @Override
    public String getDisplayName(Tile tile){
        BuildEntity entity = tile.ent();
        return Core.bundle.format("block.constructing", entity.cblock == null ? entity.previous.localizedName : entity.cblock.localizedName);
    }

    @Override
    public TextureRegion getDisplayIcon(Tile tile){
        BuildEntity entity = tile.ent();
        return (entity.cblock == null ? entity.previous : entity.cblock).icon(mindustry.ui.Cicon.full);
    }

    @Override
    public boolean isSolidFor(Tile tile){
        BuildEntity entity = tile.ent();
        return entity == null || (entity.cblock != null && entity.cblock.solid) || entity.previous == null || entity.previous.solid;
    }

    @Override
    public Cursor getCursor(Tile tile){
        return SystemCursor.hand;
    }

    @Override
    public void tapped(Tile tile, Playerc player){
        BuildEntity entity = tile.ent();

        //TODO building
        /*
        //if the target is constructible, begin constructing
        if(entity.cblock != null){
            if(player.buildWasAutoPaused && !player.isBuilding){
                player.isBuilding = true;
            }
            //player.clearBuilding();
            player.builder().addBuild(new BuildRequest(tile.x, tile.y, tile.rotation(), entity.cblock), false);
        }*/
    }

    @Override
    public void onDestroyed(Tile tile){
        Fx.blockExplosionSmoke.at(tile);

        if(!tile.floor().solid && !tile.floor().isLiquid){
            Effects.rubble(tile.drawx(), tile.drawy(), size);
        }
    }

    @Override
    public void draw(Tile tile){
        BuildEntity entity = tile.ent();

        //When breaking, don't draw the previous block... since it's the thing you were breaking
        if(entity.cblock != null && entity.previous == entity.cblock){
            return;
        }

        if(entity.previous == null || entity.cblock == null) return;

        if(Core.atlas.isFound(entity.previous.icon(Cicon.full))){
            Draw.rect(entity.previous.icon(Cicon.full), tile.drawx(), tile.drawy(), entity.previous.rotate ? tile.rotation() * 90 : 0);
        }
    }

    @Override
    public void drawLayer(Tile tile){

        BuildEntity entity = tile.ent();

        Shaders.blockbuild.color = Pal.accent;

        Block target = entity.cblock == null ? entity.previous : entity.cblock;

        if(target == null) return;

        for(TextureRegion region : target.getGeneratedIcons()){
            Shaders.blockbuild.region = region;
            Shaders.blockbuild.progress = entity.progress;

            Draw.rect(region, tile.drawx(), tile.drawy(), target.rotate ? tile.rotation() * 90 : 0);
            Draw.flush();
        }
    }

    public class BuildEntity extends TileEntity{
        /**
         * The recipe of the block that is being constructed.
         * If there is no recipe for this block, as is the case with rocks, 'previous' is used.
         */
        public @Nullable
        Block cblock;

        public float progress = 0;
        public float buildCost;
        /**
         * The block that used to be here.
         * If a non-recipe block is being deconstructed, this is the block that is being deconstructed.
         */
        public Block previous;
        public int builderID = -1;

        private float[] accumulator;
        private float[] totalAccumulator;

        public boolean construct(Unitc builder, @Nullable Tilec core, float amount, boolean configured){
            if(cblock == null){
                kill();
                return false;
            }

            if(cblock.requirements.length != accumulator.length || totalAccumulator.length != cblock.requirements.length){
                setConstruct(previous, cblock);
            }

            float maxProgress = core == null ? amount : checkRequired(core.items(), amount, false);

            for(int i = 0; i < cblock.requirements.length; i++){
                int reqamount = Math.round(state.rules.buildCostMultiplier * cblock.requirements[i].amount);
                accumulator[i] += Math.min(reqamount * maxProgress, reqamount - totalAccumulator[i] + 0.00001f); //add min amount progressed to the accumulator
                totalAccumulator[i] = Math.min(totalAccumulator[i] + reqamount * maxProgress, reqamount);
            }

            maxProgress = core == null ? maxProgress : checkRequired(core.items(), maxProgress, true);

            progress = Mathf.clamp(progress + maxProgress);
            builderID = builder.id();

            if(progress >= 1f || state.rules.infiniteResources){
                constructed(tile, cblock, builderID, tile.rotation(), builder.team(), configured);
                return true;
            }
            return false;
        }

        public void deconstruct(Unitc builder, @Nullable Tilec core, float amount){
            float deconstructMultiplier = 0.5f;

            if(cblock != null){
                ItemStack[] requirements = cblock.requirements;
                if(requirements.length != accumulator.length || totalAccumulator.length != requirements.length){
                    setDeconstruct(cblock);
                }

                //make sure you take into account that you can't deconstruct more than there is deconstructed
                float clampedAmount = Math.min(amount, progress);

                for(int i = 0; i < requirements.length; i++){
                    int reqamount = Math.round(state.rules.buildCostMultiplier * requirements[i].amount);
                    accumulator[i] += Math.min(clampedAmount * deconstructMultiplier * reqamount, deconstructMultiplier * reqamount - totalAccumulator[i]); //add scaled amount progressed to the accumulator
                    totalAccumulator[i] = Math.min(totalAccumulator[i] + reqamount * clampedAmount * deconstructMultiplier, reqamount);

                    int accumulated = (int)(accumulator[i]); //get amount

                    if(clampedAmount > 0 && accumulated > 0){ //if it's positive, add it to the core
                        if(core != null){
                            int accepting = core.tile().block().acceptStack(requirements[i].item, accumulated, core.tile(), builder);
                            core.tile().block().handleStack(requirements[i].item, accepting, core.tile(), builder);
                            accumulator[i] -= accepting;
                        }else{
                            accumulator[i] -= accumulated;
                        }
                    }
                }
            }

            progress = Mathf.clamp(progress - amount);

            if(builder instanceof Playerc){
                builderID = builder.id();
            }

            if(progress <= 0 || state.rules.infiniteResources){
                Call.onDeconstructFinish(tile, this.cblock == null ? previous : this.cblock, builderID);
            }
        }

        private float checkRequired(ItemModule inventory, float amount, boolean remove){
            float maxProgress = amount;

            for(int i = 0; i < cblock.requirements.length; i++){
                int sclamount = Math.round(state.rules.buildCostMultiplier * cblock.requirements[i].amount);
                int required = (int)(accumulator[i]); //calculate items that are required now

                if(inventory.get(cblock.requirements[i].item) == 0 && sclamount != 0){
                    maxProgress = 0f;
                }else if(required > 0){ //if this amount is positive...
                    //calculate how many items it can actually use
                    int maxUse = Math.min(required, inventory.get(cblock.requirements[i].item));
                    //get this as a fraction
                    float fraction = maxUse / (float)required;

                    //move max progress down if this fraction is less than 1
                    maxProgress = Math.min(maxProgress, maxProgress * fraction);

                    accumulator[i] -= maxUse;

                    //remove stuff that is actually used
                    if(remove){
                        inventory.remove(cblock.requirements[i].item, maxUse);
                    }
                }
                //else, no items are required yet, so just keep going
            }

            return maxProgress;
        }

        public float progress(){
            return progress;
        }

        public void setConstruct(Block previous, Block block){
            this.cblock = block;
            this.previous = previous;
            this.accumulator = new float[block.requirements.length];
            this.totalAccumulator = new float[block.requirements.length];
            this.buildCost = block.buildCost * state.rules.buildCostMultiplier;
        }

        public void setDeconstruct(Block previous){
            if(previous == null) return;
            this.previous = previous;
            this.progress = 1f;
            if(previous.buildCost >= 0.01f){
                this.cblock = previous;
                this.buildCost = previous.buildCost * state.rules.buildCostMultiplier;
            }else{
                this.buildCost = 20f; //default no-requirement build cost is 20
            }
            this.accumulator = new float[previous.requirements.length];
            this.totalAccumulator = new float[previous.requirements.length];
        }

        @Override
        public void write(DataOutput stream) throws IOException{
            super.write(stream);
            stream.writeFloat(progress);
            stream.writeShort(previous == null ? -1 : previous.id);
            stream.writeShort(cblock == null ? -1 : cblock.id);

            if(accumulator == null){
                stream.writeByte(-1);
            }else{
                stream.writeByte(accumulator.length);
                for(int i = 0; i < accumulator.length; i++){
                    stream.writeFloat(accumulator[i]);
                    stream.writeFloat(totalAccumulator[i]);
                }
            }
        }

        @Override
        public void read(DataInput stream) throws IOException{
            super.read(stream);
            progress = stream.readFloat();
            short pid = stream.readShort();
            short rid = stream.readShort();
            byte acsize = stream.readByte();

            if(acsize != -1){
                accumulator = new float[acsize];
                totalAccumulator = new float[acsize];
                for(int i = 0; i < acsize; i++){
                    accumulator[i] = stream.readFloat();
                    totalAccumulator[i] = stream.readFloat();
                }
            }

            if(pid != -1) previous = content.block(pid);
            if(rid != -1) cblock = content.block(rid);

            if(cblock != null){
                buildCost = cblock.buildCost * state.rules.buildCostMultiplier;
            }else{
                buildCost = 20f;
            }
        }
    }
}
