package org.shelterconnect.api.management;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PublicAnimalClientTest {
    final JsonMapper json=new JsonMapper();
    final PublicAnimalClient client=new PublicAnimalClient("",json);
    final String animal="442418202601187";
    @Test void filtersTheExactDogAndPreservesShelterIdentityWithoutUnneededPersonalFields() {
        var item=json.createObjectNode().put("desertionNo",animal).put("upKindCd","417000")
            .put("careRegNo","342418201300001").put("careAddr","공공 주소").put("careOwnerNm","discard");
        var payload=json.createObjectNode();payload.withObject("/response/header").put("resultCode","00");
        var items=payload.withObject("/response/body/items").putArray("item");
        items.add(json.createObjectNode().put("desertionNo","442418202601188"));items.add(item);
        var record=client.parse(animal,payload);
        assertThat(record.path("careRegNo").asText()).isEqualTo("342418201300001");
        assertThat(record.has("careOwnerNm")).isFalse();
        items.add(item);assertThatThrownBy(()->client.parse(animal,payload)).isInstanceOf(ManagementException.class);
    }
    @Test void refusesMissingShelterCodesAndNonDogRecordsAndUpstreamErrors() {
        var payload=json.createObjectNode();payload.withObject("/response/header").put("resultCode","00");
        var item=payload.withObject("/response/body/items/item").put("desertionNo",animal).put("upKindCd","417000");
        assertThatThrownBy(()->client.parse(animal,payload)).isInstanceOf(ManagementException.class);
        item.put("careRegNo","342418201300001").put("upKindCd","422400");
        assertThatThrownBy(()->client.parse(animal,payload)).isInstanceOf(ManagementException.class);
        item.put("upKindCd","417000");payload.withObject("/response/header").put("resultCode","22");
        assertThatThrownBy(()->client.parse(animal,payload)).isInstanceOf(ManagementException.class);
    }
    @Test void missingGovernmentKeyFailsBeforeSendingAnyNetworkRequest() {
        assertThatThrownBy(()->client.fetch(animal)).isInstanceOfSatisfying(ManagementException.class,
            e->{assertThat(e.status).isEqualTo(503);assertThat(e.code).isEqualTo("PUBLIC_SOURCE_NOT_CONFIGURED");});
    }
}
