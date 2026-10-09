package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Conservative silhouette support, NOT tail segmentation. Missing support means uncertain, not defective anatomy. */
final class StyledTailGeometry {
    static final String VERSION="rear-silhouette-branches-v2";
    private static final int[] DX={0,1,1,1,0,-1,-1,-1}, DY={-1,-1,0,1,1,1,0,-1};
    static JsonNode measure(JsonMapper json,List<byte[]> seeds){
        var result=(ObjectNode)StyledSeedTailEvidence.geometry(json,seeds,false);
        for(String d:StyledSeedTailEvidence.SIDES){
            var image=StyledSpriteCodec.nativeFrame(seeds.get(StyledSpriteCodec.DIRECTIONS.indexOf(d)));
            boolean[] pixels=new boolean[1024];int left=32,right=-1,bottom=-1;
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)if((image.getRGB(x,y)>>>24)!=0){pixels[y*32+x]=true;left=Math.min(left,x);right=Math.max(right,x);bottom=Math.max(bottom,y);}
            // Zhang-Suen thinning is a measurement copy only. The source PNG is never changed.
            boolean changed;
            do{changed=false;for(int phase=0;phase<2;phase++){
                var remove=new ArrayList<Integer>();
                for(int p=0;p<1024;p++)if(pixels[p]){
                    boolean[] n=new boolean[8];int count=0,transitions=0;
                    for(int k=0;k<8;k++){n[k]=at(pixels,p%32+DX[k],p/32+DY[k]);if(n[k])count++;}
                    for(int k=0;k<8;k++)if(!n[k] && n[(k+1)%8])transitions++;
                    boolean preserve=phase==0?(n[0] && n[2] && n[4]) || (n[2] && n[4] && n[6]):(n[0] && n[2] && n[6]) || (n[0] && n[4] && n[6]);
                    if(count>=2 && count<=6 && transitions==1 && !preserve)remove.add(p);
                }
                if(!remove.isEmpty()){changed=true;remove.forEach(p->pixels[p]=false);}
            }}while(changed);
            var paths=json.createArrayNode();double middle=(left+right)/2.0;int footBand=bottom-3;
            for(int p=0;p<1024;p++)if(pixels[p] && neighbors(pixels,p).size()==1 && p/32<footBand && rear(p%32,middle,d)){
                var path=new ArrayList<Integer>();path.add(p);int previous=-1,current=p;
                while(true){var next=neighbors(pixels,current);next.remove(Integer.valueOf(previous));if(next.size()!=1)break;
                    previous=current;current=next.getFirst();if(path.contains(current))break;path.add(current);
                }
                // Thinning can leave a single tail -> spine -> head path with no rump junction.
                // Measure the first qualifying spine connection, not only the far end at the head.
                // Keep the same >=4 points, >=2px rise, central-third and rear-tip constraints;
                // a descending paw-to-torso trace does not gain support from this allowance.
                int supportEnd=-1;
                for(int i=3;i<path.size();i++){
                    int point=path.get(i);
                    if(p/32+2<=point/32 && Math.abs(point%32-middle)<=(right-left)/6.0){supportEnd=i;break;}
                }
                if(supportEnd<0 && path.size()>=4 && rear(current%32,middle,d))supportEnd=path.size()-1;
                if(supportEnd>=0){
                    var points=json.createArrayNode();for(int i=0;i<=supportEnd;i++){int point=path.get(i);points.addObject().put("x",point%32).put("y",point/32);}paths.add(points);
                }
            }
            var g=(ObjectNode)result.path(d);g.put("branchVersion",VERSION).put("rearBranchSupport",!paths.isEmpty());g.set("rearBranchCandidates",paths);
        }
        return result;
    }
    private static boolean rear(int x,double middle,String d){return d.equals("west")?x>=middle:x<=middle;}
    private static boolean at(boolean[] p,int x,int y){return x>=0 && x<32 && y>=0 && y<32 && p[y*32+x];}
    private static ArrayList<Integer> neighbors(boolean[] pixels,int p){
        var result=new ArrayList<Integer>();int x=p%32,y=p/32;
        for(int k=0;k<8;k++)if(at(pixels,x+DX[k],y+DY[k]) && !(DX[k]!=0 && DY[k]!=0 && (at(pixels,x+DX[k],y) || at(pixels,x,y+DY[k]))))result.add((y+DY[k])*32+x+DX[k]);
        return result;
    }
}
