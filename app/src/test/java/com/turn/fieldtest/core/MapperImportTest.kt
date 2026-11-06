package com.turn.fieldtest.core
import com.turn.fieldtest.data.repository.MapperPackage
import com.turn.fieldtest.data.repository.MapperRoomCatalog
import com.turn.fieldtest.data.repository.SavedFloorMap
import com.turn.fieldtest.data.repository.missingMapperRooms
import com.turn.fieldtest.data.repository.destinationAreas
import com.turn.fieldtest.data.local.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
class MapperImportTest {
    @Test fun everySuppliedFloorRetainsEveryLabelIncludingRoomsWithoutPois() {
        val files=File("src/main/assets/mapper").listFiles()!!.filter {it.extension=="json"}
        assertEquals(14,files.size)
        var roomsWithoutEntrances=0
        files.forEach {file ->
            val source=Json.parseToJsonElement(file.readText()).jsonObject
            val catalog=MapperRoomCatalog.parse(source)
            assertEquals(source.getValue("floors").jsonArray.single().jsonObject.getValue("rooms").jsonArray.size,catalog.size,file.name)
            assertEquals(catalog.size,catalog.map {it.id}.distinct().size,file.name)
            val links=catalog.mapNotNull {it.sourcePlaceId}
            assertEquals(links.size,links.distinct().size,"One source entrance per room on ${file.name}")
            catalog.forEach {assertTrue(it.polygon.contains(it.labelPoint),"Label inside ${it.name}")}
            val floor=source.getValue("floors").jsonArray.single().jsonObject
            val nodes=source.getValue("nodes").jsonArray.associate {e->e.jsonObject.let {it.getValue("id").jsonPrimitive.content to it}}
            val places=source.getValue("pois").jsonArray.map {e->e.jsonObject.let {p->val n=nodes.getValue(p.getValue("nodeId").jsonPrimitive.content)
                PointOfInterestEntity("fixture-${p.getValue("id").jsonPrimitive.content}","floor-${file.name}",p.getValue("name").jsonPrimitive.content,"ROOM",n.getValue("x").jsonPrimitive.double,n.getValue("y").jsonPrimitive.double)}}
            val saved=SavedFloorMap(VenueEntity("v","Test",notes="TURN-Mapper test\n${file.readText()}",createdAtEpochMillis=1,updatedAtEpochMillis=1),
                FloorEntity("floor-${file.name}","v",file.name,0,floor.getValue("widthMetres").jsonPrimitive.double,floor.getValue("heightMetres").jsonPrimitive.double,createdAtEpochMillis=1,updatedAtEpochMillis=1),
                null,emptyList(),emptyList(),emptyList(),emptyList(),false,destinations=places)
            val completed=saved.copy(destinations=places+saved.missingMapperRooms())
            assertEquals(catalog.size,completed.destinationAreas().size,"Every label gets a stable place on ${file.name}")
            assertTrue(completed.missingMapperRooms().isEmpty(),"Upgrade is idempotent on ${file.name}")
            val renamed=completed.copy(destinations=completed.destinations.map {it.copy(name="Renamed ${it.id}")})
            assertEquals(completed.destinationAreas(),renamed.destinationAreas(),"Rename preserves source identities on ${file.name}")
            roomsWithoutEntrances+=catalog.count {it.sourcePlaceId==null}
        }
        assertTrue(roomsWithoutEntrances>100,"Regression fixture must cover the formerly omitted labels")
    }
    @Test fun rejectsWrongCoordinateFrameWithoutGuessingOrFlipping() {
        val text=File("src/main/assets/mapper/C-F4.json").readText().replace("x-right-y-up","x-right-y-down")
        assertThrows(IllegalArgumentException::class.java) {MapperPackage.parse(text)}
    }
    @Test fun suppliedFourthFloorPassesExistingGeometryAndRoutingChecks() {
        val p = MapperPackage.parse(File("src/main/assets/mapper/C-F4.json").readText())
        assertEquals(56.73, p.width); assertEquals(24.819, p.height)
        assertEquals(91,p.nodes.size); assertEquals(14,p.places.size)
    }
}
