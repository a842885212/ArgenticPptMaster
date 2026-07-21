const API = "/api/ppt-jobs";
const CONTENT_EXT = new Set([
  "md", "markdown", "pdf", "ppt", "pptx", "doc", "docx", "xls", "xlsx", "xlsm", "csv", "tsv", "html",
]);

const els = {
  form: document.getElementById("createForm"),
  semanticAck: document.getElementById("semanticAck"),
  templateFile: document.getElementById("templateFile"),
  contentFiles: document.getElementById("contentFiles"),
  templateList: document.getElementById("templateList"),
  contentList: document.getElementById("contentList"),
  createBtn: document.getElementById("createBtn"),
  createError: document.getElementById("createError"),
  jobPanel: document.getElementById("jobPanel"),
  jobId: document.getElementById("jobId"),
  jobStatus: document.getElementById("jobStatus"),
  analysisSummary: document.getElementById("analysisSummary"),
  analysisDl: document.getElementById("analysisDl"),
  progressDl: document.getElementById("progressDl"),
  confirmPanel: document.getElementById("confirmPanel"),
  confirmSummary: document.getElementById("confirmSummary"),
  rejectFeedback: document.getElementById("rejectFeedback"),
  approveBtn: document.getElementById("approveBtn"),
  rejectBtn: document.getElementById("rejectBtn"),
  confirmError: document.getElementById("confirmError"),
  resumeBtn: document.getElementById("resumeBtn"),
  downloadLink: document.getElementById("downloadLink"),
  jobError: document.getElementById("jobError"),
};

/** @type {{ jobId?: string, statusRank: number, es?: EventSource, reconnectAttempt: number }} */
const state = { statusRank: -1, reconnectAttempt: 0 };

function extOf(name) {
  const i = name.lastIndexOf(".");
  return i >= 0 ? name.slice(i + 1).toLowerCase() : "";
}

function renderFileList(ul, files, role) {
  ul.innerHTML = "";
  for (const f of files) {
    const li = document.createElement("li");
    li.textContent = `${role}: ${f.name} (${f.size} bytes)`;
    ul.appendChild(li);
  }
}

function validateLocal() {
  const errors = [];
  const template = els.templateFile.files?.[0];
  const contents = [...(els.contentFiles.files || [])];
  if (!template) {
    errors.push("请选择一个模板 PPTX");
  } else if (extOf(template.name) !== "pptx") {
    errors.push("模板必须是 .pptx");
  }
  if (contents.length === 0) {
    errors.push("请至少选择一个内容资料文件");
  } else {
    for (const f of contents) {
      if (!CONTENT_EXT.has(extOf(f.name))) {
        errors.push(`内容文件类型不受支持: ${f.name}`);
      }
    }
  }
  if (!els.semanticAck.checked) {
    errors.push("请先确认模式语义");
  }
  return errors;
}

function refreshCreateEnabled() {
  const errors = validateLocal();
  els.createBtn.disabled = errors.length > 0;
  if (errors.length && document.activeElement === els.createBtn) {
    show(els.createError, errors[0]);
  } else {
    hide(els.createError);
  }
}

function show(el, text) {
  el.hidden = false;
  if (text != null) el.textContent = text;
}

function hide(el) {
  el.hidden = true;
  el.textContent = "";
}

function fillDl(dl, entries) {
  dl.innerHTML = "";
  for (const [k, v] of entries) {
    if (v == null || v === "") continue;
    const dt = document.createElement("dt");
    dt.textContent = k;
    const dd = document.createElement("dd");
    dd.textContent = String(v);
    dl.append(dt, dd);
  }
}

const STATUS_RANK = {
  ACCEPTED: 0,
  PREPARING: 1,
  WAITING_CONFIRMATION: 2,
  RUNNING_AGENT: 3,
  EXPORTING: 4,
  COMPLETED: 5,
  FAILED: 5,
  CANCELLED: 5,
};

function applyJob(job, { force = false } = {}) {
  const rank = STATUS_RANK[job.status] ?? 0;
  if (!force && rank < state.statusRank && job.status !== "FAILED") {
    return;
  }
  state.statusRank = rank;
  state.jobId = job.id;
  els.jobId.textContent = job.id;
  els.jobStatus.textContent = job.status;
  show(els.jobPanel);

  const progress = job.templateFillProgress || {};
  fillDl(els.progressDl, [
    ["fillPlanStatus", job.fillPlanStatus ?? progress.fillPlanStatus],
    ["templateSlideCount", progress.templateSlideCount],
    ["planSlideCount", progress.planSlideCount],
    ["notesMappingCount", progress.notesMappingCount],
    ["tableMappingCount", progress.tableMappingCount],
    ["chartMappingCount", progress.chartMappingCount],
    ["capacityRiskCount", progress.capacityRiskCount],
    ["constraintValidationStatus", progress.constraintValidationStatus],
    ["readbackValidationStatus", progress.readbackValidationStatus],
    ["validationErrorCount", progress.validationErrorCount],
    ["exportFileName", progress.exportFileName],
  ]);

  if (job.templateAnalysisReady || progress.templateAnalysisReady) {
    show(els.analysisSummary);
    const summary = job.templateAnalysisSummary || progress;
    fillDl(els.analysisDl, [
      ["templateSlideCount", summary.templateSlideCount ?? progress.templateSlideCount],
      ["format", summary.format ?? summary.pageFormat],
      ["textSlotCount", summary.textSlotCount],
      ["tableCount", summary.tableCount],
      ["chartCount", summary.chartCount],
      ["analysisVersion", summary.analysisVersion],
      ["constraintValidationStatus", progress.constraintValidationStatus],
    ]);
  }

  const waiting =
    job.status === "WAITING_CONFIRMATION" &&
    job.currentConfirmationId &&
    (job.confirmationPayload?.stage === "template_fill_plan" ||
      job.confirmationPayload?.contextData?.type === "template_fill_plan");
  if (waiting) {
    show(els.confirmPanel);
    els.confirmSummary.textContent = JSON.stringify(job.confirmationPayload, null, 2);
  } else {
    hide(els.confirmPanel);
  }

  if (job.resumable) {
    els.resumeBtn.hidden = false;
  } else {
    els.resumeBtn.hidden = true;
  }

  const downloadReady =
    job.status === "COMPLETED" &&
    (progress.readbackValidationStatus === "PASSED" ||
      progress.readbackValidationStatus === "OK" ||
      progress.readbackValidationStatus === "SUCCESS" ||
      job.downloadReady === true ||
      !!progress.exportFileName);
  if (downloadReady) {
    els.downloadLink.hidden = false;
    els.downloadLink.href = `${API}/${job.id}/download`;
  } else {
    els.downloadLink.hidden = true;
  }

  if (job.status === "FAILED") {
    show(els.jobError, job.errorMessage || "任务失败");
  } else {
    hide(els.jobError);
  }
}

async function fetchJob(jobId) {
  const res = await fetch(`${API}/${jobId}`);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || `查询失败 (${res.status})`);
  }
  return res.json();
}

function connectSse(jobId) {
  if (state.es) {
    state.es.close();
  }
  const es = new EventSource(`${API}/${jobId}/events`);
  state.es = es;
  es.onmessage = async () => {
    try {
      const job = await fetchJob(jobId);
      applyJob(job);
      state.reconnectAttempt = 0;
      if (job.status === "COMPLETED" || job.status === "FAILED" || job.status === "CANCELLED") {
        es.close();
      }
    } catch (err) {
      show(els.jobError, err.message);
    }
  };
  es.onerror = () => {
    es.close();
    const attempt = ++state.reconnectAttempt;
    if (attempt > 6) {
      show(els.jobError, "事件流中断，请刷新或手动恢复");
      return;
    }
    const delay = Math.min(1000 * 2 ** (attempt - 1), 15000);
    setTimeout(async () => {
      try {
        const job = await fetchJob(jobId);
        applyJob(job, { force: true });
        if (job.status !== "COMPLETED" && job.status !== "FAILED" && job.status !== "CANCELLED") {
          connectSse(jobId);
        }
      } catch (err) {
        show(els.jobError, err.message);
      }
    }, delay);
  };
}

els.templateFile.addEventListener("change", () => {
  const files = [...(els.templateFile.files || [])];
  if (files.length > 1) {
    show(els.createError, "模板区只能选择一个文件");
    els.templateFile.value = "";
    renderFileList(els.templateList, [], "模板");
  } else {
    renderFileList(els.templateList, files, "模板");
  }
  refreshCreateEnabled();
});

els.contentFiles.addEventListener("change", () => {
  renderFileList(els.contentList, [...(els.contentFiles.files || [])], "内容");
  refreshCreateEnabled();
});

els.semanticAck.addEventListener("change", refreshCreateEnabled);

els.form.addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const errors = validateLocal();
  if (errors.length) {
    show(els.createError, errors[0]);
    return;
  }
  hide(els.createError);
  els.createBtn.disabled = true;
  const fd = new FormData();
  fd.append("templateFile", els.templateFile.files[0]);
  for (const f of els.contentFiles.files) {
    fd.append("files", f);
  }
  fd.append("workflowMode", "template-fill");
  const projectName = document.getElementById("projectName").value.trim();
  const instruction = document.getElementById("instruction").value.trim();
  const constraints = document.getElementById("templateConstraints").value.trim();
  if (projectName) fd.append("projectName", projectName);
  if (instruction) fd.append("instruction", instruction);
  if (constraints) fd.append("templateConstraints", constraints);

  try {
    const res = await fetch(API, { method: "POST", body: fd });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      if (res.status === 503) {
        throw new Error(body.message || "模板填充功能未启用");
      }
      if (res.status === 403) {
        throw new Error(body.message || "当前身份不在灰度范围");
      }
      throw new Error(body.message || `创建失败 (${res.status})`);
    }
    state.statusRank = -1;
    applyJob(body, { force: true });
    connectSse(body.id);
  } catch (err) {
    show(els.createError, err.message);
  } finally {
    refreshCreateEnabled();
  }
});

async function submitConfirmation(approved) {
  hide(els.confirmError);
  try {
    const job = await fetchJob(state.jobId);
    const confirmationId = job.currentConfirmationId;
    if (!confirmationId) {
      throw new Error("当前没有待确认计划，已刷新状态");
    }
    const res = await fetch(`${API}/${state.jobId}/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        confirmationId,
        approved,
        action: approved ? "APPROVE" : "CANCEL",
        comment: approved ? null : els.rejectFeedback.value.trim() || null,
      }),
    });
    const body = await res.json().catch(() => ({}));
    if (res.status === 409 || res.status === 400) {
      show(els.confirmError, body.message || "确认冲突，请查看最新计划");
      applyJob(await fetchJob(state.jobId), { force: true });
      return;
    }
    if (!res.ok) {
      throw new Error(body.message || `确认失败 (${res.status})`);
    }
    applyJob(body, { force: true });
  } catch (err) {
    show(els.confirmError, err.message);
  }
}

els.approveBtn.addEventListener("click", () => submitConfirmation(true));
els.rejectBtn.addEventListener("click", () => submitConfirmation(false));

els.resumeBtn.addEventListener("click", async () => {
  hide(els.jobError);
  try {
    const res = await fetch(`${API}/${state.jobId}/resume`, { method: "POST" });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.message || `恢复失败 (${res.status})`);
    applyJob(body, { force: true });
    connectSse(body.id);
  } catch (err) {
    show(els.jobError, err.message);
  }
});

refreshCreateEnabled();
