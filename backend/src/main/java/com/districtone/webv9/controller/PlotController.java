package com.districtone.webv9.controller;
import com.districtone.webv9.model.Plot; import com.districtone.webv9.repo.PlotRepository; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/v1/plots") public class PlotController { private final PlotRepository repo; public PlotController(PlotRepository r){repo=r;} @GetMapping public List<Plot> list(){return repo.findAll();} }
