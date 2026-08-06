const API = "/api/ppt-jobs";

const els = {
  form: document.getElementById("createForm"),
  semanticAck: document.getElementById("semanticAck"),
  sourceFile: document.getElementById("sourceFile"),
  sourceList: document.getElementById("sourceList"),
  createBtn: document.getElementById("createBtn"),
  createError: document.getElementById("createError"),
  jobPanel: document.getElementById("jobPanel"),
  jobId: document.getElementById("jobId"),
  jobStatus: document.getElementById("jobStatus"),
  analysisSummary: document.getElementById("analysisSummary"),
  analysisDl: document.getElementById("analysisDl"),
  progressDl: document.getElementById("progressDl"),
  confirmPanel: document.getElementById("confirmPanel"),
  confirmTitle: document.getElementById("confirmTitle"),
  confirmSummary: document.getElementById("confirmSummary"),
  rejectFeedback: document.getElementById("rejectFeedback"),
  approveBtn: document.getElementById("approveBtn"),
  rejectBtn: document.getElementById("rejectBtn"),
  confirmError: document.getElementById("confirmError"),
  resumeBtn: document.getElementById("resumeBtn"),
  downloadLink: document.getElementById("downloadLink"),
  jobError: document.getElementById("jobError"),
};

const state = { statusRank: -1, reconnectAttempt: 0 };

function extOf(name) {
  const i = name.lastIndexOf(".");
  return i >= 0 ? name.slice(i + 1).toLowerCase() : "";
}

function renderFileList(ul, files) {
  ul.innerHTML = "";
  for (const f of files) {
    const li = document.createElement("li");
    li.textContent = `${f.name} (${f.size} bytes)`;
    ul.appendChild(li);
  }
}

function validateLocal() {
  const errors = [];
  const files = [...(els.sourceFile.files || [])];
  if (files.length !== 1) {
    errors.push("请选择且仅选择一个源 PPTX");
  } else if (extOf(files[0].name) !== "pptx") {
    errors.push("源文件必须是 .pptx");
  }
  if (!els.semanticAck.checked) {
    errors.push("请先确认硬约束：不改版式与可见内容，仅追加备注/旁白/切换");
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

function contextData(job) {
  return job.confirmationPayload?.contextData || {};
}

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

  const analysis = job.nativeEnhanceAnalysis || {};
  fillDl(els.progressDl, [
    ["planStatus", analysis.enhancementPlanStatus || analysis.planStatus],
    ["sourcePageCount", analysis.sourcePageCount],
    ["enabledModules", Array.isArray(analysis.enabledModules) ? analysis.enabledModules.join(",") : analysis.enabledModules],
    ["audioVoiceStatus", analysis.audioVoiceStatus],
    ["outputValidation", analysis.outputValidation?.status || analysis.outputValidationStatus],
    ["resumable", job.resumable],
    ["downloadReady", job.downloadReady],
  ]);

  if (analysis && Object.keys(analysis).length) {
    show(els.analysisSummary);
    fillDl(els.analysisDl, [
      ["version", analysis.version],
      ["digest", analysis.digest],
      ["coverage", analysis.coverageSummary],
    ]);
  }

  const stage = job.confirmationPayload?.stage;
  const ctx = contextData(job);
  if (job.status === "WAITING_CONFIRMATION" && (stage === "enhancement_plan_confirmation" || stage === "audio_voice_confirmation")) {
    show(els.confirmPanel);
    els.confirmTitle.textContent = stage === "audio_voice_confirmation" ? "确认旁白语音" : "确认增强计划";
    els.confirmSummary.textContent = JSON.stringify({
      stage,
      version: ctx.version,
      digest: ctx.digest,
      modules: ctx.modules || ctx.enabledModules,
      provider: ctx.provider,
      voice: ctx.voice,
      type: ctx.type,
    }, null, 2);
    state.planVersion = ctx.version;
    state.planDigest = ctx.digest;
    els.rejectBtn.hidden = stage === "audio_voice_confirmation";
  } else {
    hide(els.confirmPanel);
  }

  if (job.resumable) {
    show(els.resumeBtn);
  } else {
    hide(els.resumeBtn);
  }

  if (job.downloadReady || (job.status === "COMPLETED" && job.exportReady !== false)) {
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

async function fetchJob(id) {
  const res = await fetch(`${API}/${id}`);
  if (!res.ok) throw new Error(`详情失败 HTTP ${res.status}`);
  return res.json();
}

function connectSse(jobId) {
  if (state.es) state.es.close();
  const es = new EventSource(`${API}/${jobId}/events`);
  state.es = es;
  es.onmessage = async () => {
    try {
      const job = await fetchJob(jobId);
      applyJob(job);
      if (["COMPLETED", "FAILED", "CANCELLED"].includes(job.status)) {
        es.close();
      }
    } catch (err) {
      show(els.jobError, String(err.message || err));
    }
  };
  es.onerror = () => {
    es.close();
    state.reconnectAttempt += 1;
    if (state.reconnectAttempt <= 5) {
      setTimeout(() => connectSse(jobId), Math.min(1000 * state.reconnectAttempt, 5000));
    }
  };
}

els.sourceFile.addEventListener("change", () => {
  renderFileList(els.sourceList, [...(els.sourceFile.files || [])]);
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
  const fd = new FormData();
  fd.append("files", els.sourceFile.files[0]);
  fd.append("workflowMode", "native-enhance-pptx");
  const projectName = document.getElementById("projectName").value.trim();
  const instruction = document.getElementById("instruction").value.trim();
  if (projectName) fd.append("projectName", projectName);
  if (instruction) fd.append("instruction", instruction);
  els.createBtn.disabled = true;
  try {
    const res = await fetch(API, { method: "POST", body: fd });
    if (res.status === 503) {
      show(els.createError, "原生增强当前不可用（功能关闭或部署未开放）");
      return;
    }
    if (res.status === 403) {
      show(els.createError, "无创建资格（需测试租户或管理员）");
      return;
    }
    if (!res.ok) {
      const body = await res.text();
      show(els.createError, `创建失败 HTTP ${res.status}: ${body.slice(0, 200)}`);
      return;
    }
    const job = await res.json();
    state.statusRank = -1;
    state.reconnectAttempt = 0;
    applyJob(job, { force: true });
    connectSse(job.id);
  } catch (err) {
    show(els.createError, String(err.message || err));
  } finally {
    refreshCreateEnabled();
  }
});

els.approveBtn.addEventListener("click", async () => {
  if (!state.jobId) return;
  hide(els.confirmError);
  const answers = {};
  if (state.planVersion != null) answers.version = state.planVersion;
  if (state.planDigest) answers.digest = state.planDigest;
  try {
    const res = await fetch(`${API}/${state.jobId}/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        confirmationId: (await fetchJob(state.jobId)).confirmationId,
        action: "APPROVE",
        answers,
      }),
    });
    if (!res.ok) {
      show(els.confirmError, `确认失败 HTTP ${res.status}`);
      return;
    }
    applyJob(await res.json(), { force: true });
  } catch (err) {
    show(els.confirmError, String(err.message || err));
  }
});

els.rejectBtn.addEventListener("click", async () => {
  if (!state.jobId) return;
  const feedback = els.rejectFeedback.value.trim();
  if (!feedback) {
    show(els.confirmError, "驳回需要填写反馈");
    return;
  }
  try {
    const job = await fetchJob(state.jobId);
    const res = await fetch(`${API}/${state.jobId}/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        confirmationId: job.confirmationId,
        action: "REQUEST_REVISION",
        overallComment: feedback,
        comment: feedback,
      }),
    });
    if (!res.ok) {
      show(els.confirmError, `驳回失败 HTTP ${res.status}`);
      return;
    }
    applyJob(await res.json(), { force: true });
  } catch (err) {
    show(els.confirmError, String(err.message || err));
  }
});

els.resumeBtn.addEventListener("click", async () => {
  if (!state.jobId) return;
  try {
    const res = await fetch(`${API}/${state.jobId}/resume`, { method: "POST" });
    if (!res.ok) {
      show(els.jobError, `恢复失败 HTTP ${res.status}`);
      return;
    }
    applyJob(await res.json(), { force: true });
    connectSse(state.jobId);
  } catch (err) {
    show(els.jobError, String(err.message || err));
  }
});

refreshCreateEnabled();
