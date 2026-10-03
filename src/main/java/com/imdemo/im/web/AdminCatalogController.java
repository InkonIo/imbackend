package com.imdemo.im.web;

import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.service.CatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCatalogController {

    private final CatalogService catalog;

    @GetMapping("/cities")
    public List<CityDto> cities() {
        return catalog.cities();
    }

    @PostMapping("/cities")
    public CityDto createCity(@Valid @RequestBody CityRequest r) {
        return catalog.createCity(r);
    }

    @PutMapping("/cities/{id}")
    public CityDto updateCity(@PathVariable Long id, @Valid @RequestBody CityRequest r) {
        return catalog.updateCity(id, r);
    }

    @DeleteMapping("/cities/{id}")
    public ResponseEntity<Void> deleteCity(@PathVariable Long id) {
        catalog.deleteCity(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/outlets")
    public List<OutletDto> outlets() {
        return catalog.outlets();
    }

    @PostMapping("/outlets")
    public OutletDto createOutlet(@Valid @RequestBody OutletRequest r) {
        return catalog.createOutlet(r);
    }

    @PutMapping("/outlets/{id}")
    public OutletDto updateOutlet(@PathVariable Long id, @Valid @RequestBody OutletRequest r) {
        return catalog.updateOutlet(id, r);
    }

    @DeleteMapping("/outlets/{id}")
    public ResponseEntity<Void> deleteOutlet(@PathVariable Long id) {
        catalog.deleteOutlet(id);
        return ResponseEntity.noContent().build();
    }
}