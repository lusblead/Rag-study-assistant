package com.rag.backend.paper;

import com.rag.backend.common.Result;
import com.rag.backend.paper.model.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/papers")
public class PaperController {
    private final PaperService service;
    public PaperController(PaperService service){this.service=service;}
    @PostMapping("/generate") public Result<PaperDetail> generate(@RequestBody PaperRequests.Generate request){return Result.ok(service.generate(request));}
    @GetMapping public Result<List<Paper>> list(@RequestParam Long courseId,@RequestParam(required=false) String subject){return Result.ok(service.list(courseId,subject));}
    @GetMapping("/{id}") public Result<PaperDetail> detail(@PathVariable Long id){return Result.ok(service.detail(id));}
    @PutMapping("/{id}") public Result<PaperDetail> update(@PathVariable Long id,@RequestBody PaperRequests.Update request){return Result.ok(service.update(id,request));}
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable Long id){service.delete(id);return Result.ok();}
    @PostMapping("/{id}/questions") public Result<PaperDetail> add(@PathVariable Long id,@RequestBody PaperRequests.AddQuestion request){return Result.ok(service.addQuestion(id,request));}
    @PutMapping("/{id}/questions/reorder") public Result<PaperDetail> reorder(@PathVariable Long id,@RequestBody PaperRequests.Reorder request){return Result.ok(service.reorder(id,request));}
    @DeleteMapping("/{id}/questions/{questionId}") public Result<Void> remove(@PathVariable Long id,@PathVariable Long questionId){service.removeQuestion(id,questionId);return Result.ok();}
}
