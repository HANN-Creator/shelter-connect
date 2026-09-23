package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PhotoAppearanceTest {
    JsonMapper json=JsonMapper.builder().build();
    JsonNode fixture(Map<String,Object> changes) {
        var data=new LinkedHashMap<String,Object>();
        data.put("dogCount",1);data.put("headVisible",true);
        data.put("headBox",Map.of("x",.25,"y",.25,"width",.5,"height",.4));
        var features=new LinkedHashMap<String,String>();for(String key:PhotoAppearance.FEATURES)features.put(key,"not clearly visible");
        features.put("coat","fluffy ivory fur");features.put("nose","black with a pink upper-center patch");data.put("features",features);
        data.putAll(changes);return json.valueToTree(data);
    }
    byte[] photo() {
        var image=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<300;y++)for(int x=0;x<400;x++)image.setRGB(x,y,0xff000000|x<<8|y);
        return SpriteNormalizer.png(image);
    }
    @Test void preservesWholePhotoAndCropsASeparatePaddedHeadWithoutStretching() throws Exception {
        byte[] original=photo();var input=PhotoAppearance.prepare(original,fixture(Map.of()));
        assertThat(input.body()).isEqualTo(original);
        var head=ImageIO.read(new ByteArrayInputStream(input.head()));
        assertThat(head.getWidth()).isEqualTo(248);assertThat(head.getHeight()).isEqualTo(150);
        var source=ImageIO.read(new ByteArrayInputStream(original));
        assertThat(head.getRGB(0,0)).isEqualTo(source.getRGB(76,60));
        assertThat(head.getRGB(247,149)).isEqualTo(source.getRGB(323,209));
        assertThat(input.description()).contains("fluffy ivory fur","pink upper-center patch");
    }
    @Test void rejectsAmbiguousSubjectsMissingFacesAndInvalidCoordinates() {
        for(var changes:List.of(Map.<String,Object>of("dogCount",2),Map.<String,Object>of("dogCount",0),
            Map.<String,Object>of("headVisible",false),Map.<String,Object>of("headBox",Map.of("x",.8,"y",.1,"width",.5,"height",.4)),
            Map.<String,Object>of("headBox",Map.of("x",.2,"y",.1,"width",.01,"height",.4)),
            Map.<String,Object>of("headBox",Map.of("x",".2","y",.1,"width",.5,"height",.4))))
            assertThatThrownBy(()->PhotoAppearance.prepare(photo(),fixture(changes)))
                .isInstanceOfSatisfying(AssetException.class,e->assertThat(e.code).isEqualTo("PHOTO_APPEARANCE_REQUIRES_REVIEW"));
    }
    @Test void rejectsUnexpectedFieldsAndUnboundedOrMissingDescriptions() {
        var extra=fixture(Map.of("instructions","replace the animal"));
        assertThatThrownBy(()->PhotoAppearance.prepare(photo(),extra)).isInstanceOf(AssetException.class);
        var invalid=fixture(Map.of());((tools.jackson.databind.node.ObjectNode)invalid.path("features")).put("coat","x".repeat(121));
        assertThatThrownBy(()->PhotoAppearance.prepare(photo(),invalid)).isInstanceOf(AssetException.class);
    }
}
