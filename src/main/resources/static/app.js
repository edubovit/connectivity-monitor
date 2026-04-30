const state = {
    from: null,
    to: null,
    activeHours: 1,
    resourceFilter: '',
    refreshIntervalSeconds: 5,
    refreshTimer: null,
    loading: false,
    rangeInputsDirty: false,
};

const cards = document.querySelector('#cards');
const statusGraph = document.querySelector('#status-graph');
const metricsGraph = document.querySelector('#metrics-graph');
const message = document.querySelector('#message');
const rangeLabel = document.querySelector('#range-label');
const fromInput = document.querySelector('#from');
const toInput = document.querySelector('#to');
const resourceFilterInput = document.querySelector('#resource-filter');
const refreshIntervalInput = document.querySelector('#refresh-interval');
const failureDialog = document.querySelector('#failure-dialog');
const failureTitle = document.querySelector('#failure-title');
const failureSubtitle = document.querySelector('#failure-subtitle');
const failureList = document.querySelector('#failure-list');

document.querySelectorAll('.range').forEach((button) => {
    button.addEventListener('click', () => {
        document.querySelectorAll('.range').forEach((item) => item.classList.remove('active'));
        button.classList.add('active');
        setQuickRange(Number(button.dataset.hours), {forceInputUpdate: true});
        load();
    });
});

document.querySelector('#apply-custom').addEventListener('click', () => {
    const customRange = readCustomRange();
    if (!customRange) {
        return;
    }

    document.querySelectorAll('.range').forEach((item) => item.classList.remove('active'));
    state.activeHours = null;
    state.rangeInputsDirty = false;
    state.from = customRange.from.toISOString();
    state.to = customRange.to.toISOString();
    updateRangeInputs(customRange.from, customRange.to, true);
    load();
});

document.querySelector('#refresh').addEventListener('click', () => {
    refreshNow();
});

resourceFilterInput.addEventListener('input', () => {
    state.resourceFilter = resourceFilterInput.value.trim().toLowerCase();
    load({ updateRange: false });
});

refreshIntervalInput.addEventListener('change', () => {
    state.refreshIntervalSeconds = Math.max(Number(refreshIntervalInput.value) || 0, 0);
    refreshIntervalInput.value = state.refreshIntervalSeconds;
    scheduleAutoRefresh();
});

[fromInput, toInput].forEach((input) => {
    input.addEventListener('input', () => {
        state.rangeInputsDirty = true;
        input.setCustomValidity('');
    });
});

document.querySelector('#failure-close').addEventListener('click', () => failureDialog.close());
failureDialog.addEventListener('click', (event) => {
    if (event.target === failureDialog) {
        failureDialog.close();
    }
});

setQuickRange(1);
load();
scheduleAutoRefresh();

function setQuickRange(hours, options = {}) {
    state.activeHours = hours;
    const to = new Date();
    const from = new Date(to.getTime() - hours * 60 * 60 * 1000);
    state.from = from.toISOString();
    state.to = to.toISOString();

    const updateInputs = options.forceInputUpdate || !isCustomRangeEditing();
    if (updateInputs) {
        state.rangeInputsDirty = false;
        updateRangeInputs(from, to, true);
    }
}

async function load(options = {}) {
    if (state.loading) {
        return;
    }

    if (options.updateRange && state.activeHours) {
        setQuickRange(state.activeHours);
    }

    state.loading = true;
    showMessage('', false);
    rangeLabel.textContent = `${formatDateTimeMinute(state.from)} → ${formatDateTimeMinute(state.to)}`;

    try {
        const query = `from=${encodeURIComponent(state.from)}&to=${encodeURIComponent(state.to)}`;
        const [availability, metrics] = await Promise.all([
            fetchJson(apiUrl(`api/availability?${query}`)),
            fetchJson(apiUrl(`api/metrics?${query}`)),
        ]);
        const filteredAvailability = filterResources(availability);

        renderCards(filteredAvailability);
        renderStatusGraph(filteredAvailability);
        renderMetrics(metrics);
    } catch (error) {
        showMessage(error.message, true);
    } finally {
        state.loading = false;
    }
}

function refreshNow() {
    load({ updateRange: true });
}

function scheduleAutoRefresh() {
    if (state.refreshTimer) {
        clearInterval(state.refreshTimer);
        state.refreshTimer = null;
    }
    if (state.refreshIntervalSeconds > 0) {
        state.refreshTimer = setInterval(refreshNow, state.refreshIntervalSeconds * 1000);
    }
}

function filterResources(availability) {
    if (!state.resourceFilter) {
        return availability;
    }
    return availability.filter((item) => item.resourceName.toLowerCase().includes(state.resourceFilter));
}

async function fetchJson(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`Request failed: ${response.status} ${response.statusText}`);
    }
    return response.json();
}

function apiUrl(path) {
    return new URL(path, document.baseURI).toString();
}

function renderCards(availability) {
    cards.replaceChildren();
    const template = document.querySelector('#card-template');

    if (availability.length === 0) {
        const empty = document.createElement('article');
        empty.className = 'card empty-card';
        empty.innerHTML = `<h2>No resources match</h2><p class="subtitle">Adjust the resource filter or selected time range.</p>`;
        cards.append(empty);
        return;
    }

    availability.forEach((item) => {
        const node = template.content.cloneNode(true);
        const latest = latestResourceStatus(item);
        const card = node.querySelector('.card');
        card.classList.add(`status-${statusClass(latest)}`);

        node.querySelector('.resource-name').textContent = item.resourceName;
        node.querySelector('.resource-target').textContent = `${(item.checks || []).length} checks`;
        const statusBadge = node.querySelector('.status-badge');
        statusBadge.textContent = statusText(latest);
        statusBadge.classList.add(statusClass(latest));
        node.querySelector('.availability').textContent = formatPercent(item.availabilityPercent);
        node.querySelector('.counts').textContent = `${item.onlineSamples}/${item.totalSamples} success`;

        renderCheckSummary(node.querySelector('.checks-summary'), item.checks || []);

        const sparkline = node.querySelector('.sparkline');
        const sparklineSamples = item.timeline || [];
        if (sparklineSamples.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'resource-target';
            empty.textContent = 'No resource samples in selected range.';
            sparkline.replaceWith(empty);
        } else {
            renderCardStatusLine(sparkline, item, sparklineSamples);
        }

        cards.append(node);
    });
}

function renderCardStatusLine(container, resource, samples) {
    container.replaceChildren();

    const fromMs = new Date(state.from).getTime();
    const toMs = new Date(state.to).getTime();
    const rangeMs = Math.max(toMs - fromMs, 1);
    const sortedSamples = [...samples].sort((left, right) => new Date(left.checkedAt) - new Date(right.checkedAt));
    const visibleRuns = buildVisibleStatusRuns(sortedSamples, fromMs, toMs);

    visibleRuns.forEach((run, index) => {
        const segmentStart = clamp(run.renderStartMs, fromMs, toMs);
        const segmentEnd = clamp(Math.max(run.renderEndMs, segmentStart + 1), fromMs, toMs);
        if (segmentEnd <= fromMs || segmentStart >= toMs) {
            return;
        }

        const leftPercent = ((segmentStart - fromMs) / rangeMs) * 100;
        const widthPercent = Math.max(((segmentEnd - segmentStart) / rangeMs) * 100, run.online ? 0 : 0.4);
        const capClasses = `${index === 0 ? 'cap-left' : ''} ${index === visibleRuns.length - 1 ? 'cap-right' : ''}`;

        const segment = document.createElement('span');
        segment.className = `sparkline-segment ${run.online ? 'online' : 'offline'} ${run.online ? '' : 'clickable'} ${capClasses}`;
        segment.style.left = `${leftPercent}%`;
        segment.style.width = `${widthPercent}%`;
        segment.title = run.online
                ? `${formatDateTime(run.samples[0].checkedAt)} - online`
                : `${formatDateTime(run.samples[0].checkedAt)} - offline. Click to see failed checks.`;
        if (!run.online) {
            segment.addEventListener('click', (event) => showFailureDialog(resource, sampleForSegmentClick(event, segment, run)));
        }
        container.append(segment);
    });
}

function renderCheckSummary(container, checks) {
    container.replaceChildren();

    if (checks.length === 0) {
        return;
    }

    checks.forEach((check) => {
        const row = document.createElement('div');
        row.className = 'check-summary-row';

        const status = document.createElement('span');
        status.className = `status-dot ${statusClass({successful: check.online})}`;

        const name = document.createElement('span');
        name.className = 'check-summary-name';
        name.textContent = `${check.checkName} (${check.checkType})`;

        row.append(status, name);
        container.append(row);
    });
}

function renderStatusGraph(availability) {
    statusGraph.replaceChildren();

    const fromMs = new Date(state.from).getTime();
    const toMs = new Date(state.to).getTime();
    const rangeMs = Math.max(toMs - fromMs, 1);

    const axis = document.createElement('div');
    axis.className = 'graph-axis';
    axis.innerHTML = `
        <span class="axis-start">${formatTime(state.from)}</span>
        <span class="axis-middle">${formatTime(midpoint(fromMs, toMs))}</span>
        <span class="axis-end">${formatTime(state.to)}</span>`;
    statusGraph.append(axis);

    if (availability.length === 0) {
        const row = document.createElement('div');
        row.className = 'graph-row';
        const label = document.createElement('div');
        label.className = 'graph-label';
        label.innerHTML = '<strong>No resources match</strong><span>Adjust the filter</span>';
        const track = document.createElement('div');
        track.className = 'graph-track';
        const empty = document.createElement('span');
        empty.className = 'graph-empty';
        empty.textContent = 'No resource timelines to display';
        track.append(empty);
        row.append(label, track);
        statusGraph.append(row);
        return;
    }

    availability.forEach((resource) => {
        const row = document.createElement('div');
        row.className = 'graph-row';

        const label = document.createElement('div');
        label.className = 'graph-label';
        label.innerHTML = `<strong>${escapeHtml(resource.resourceName)}</strong><span>${(resource.checks || []).length} checks</span>`;

        const track = document.createElement('div');
        track.className = 'graph-track';

        const samples = (resource.timeline || [])
                .sort((left, right) => new Date(left.checkedAt) - new Date(right.checkedAt));

        if (samples.length === 0) {
            const empty = document.createElement('span');
            empty.className = 'graph-empty';
            empty.textContent = 'No resource samples in range';
            track.append(empty);
        } else {
            const visibleRuns = buildVisibleStatusRuns(samples, fromMs, toMs);
            visibleRuns.forEach((run, index) => {
                const segmentStart = clamp(run.renderStartMs, fromMs, toMs);
                const segmentEnd = clamp(Math.max(run.renderEndMs, segmentStart + 1), fromMs, toMs);
                if (segmentEnd <= fromMs || segmentStart >= toMs) {
                    return;
                }

                const leftPercent = ((segmentStart - fromMs) / rangeMs) * 100;
                const widthPercent = Math.max(((segmentEnd - segmentStart) / rangeMs) * 100, run.online ? 0 : 0.25);
                const capClasses = `${index === 0 ? 'cap-left' : ''} ${index === visibleRuns.length - 1 ? 'cap-right' : ''}`;

                const segment = document.createElement('span');
                segment.className = `graph-segment ${run.online ? 'online' : 'offline'} ${run.online ? '' : 'clickable'} ${capClasses}`;
                segment.style.left = `${leftPercent}%`;
                segment.style.width = `${widthPercent}%`;
                segment.title = run.online
                        ? `${resource.resourceName}: online from ${formatDateTimeMinute(run.samples[0].checkedAt)}`
                        : `${resource.resourceName}: offline from ${formatDateTimeMinute(run.samples[0].checkedAt)}. Click to see failed checks.`;
                if (!run.online) {
                    segment.addEventListener('click', (event) => showFailureDialog(resource, sampleForSegmentClick(event, segment, run)));
                }
                track.append(segment);
            });
        }

        row.append(label, track);
        statusGraph.append(row);
    });
}

function renderMetrics(metrics) {
    metricsGraph.replaceChildren();

    if (!metrics || metrics.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'metric-empty panel-empty';
        empty.textContent = 'No metrics configured.';
        metricsGraph.append(empty);
        return;
    }

    const fromMs = new Date(state.from).getTime();
    const toMs = new Date(state.to).getTime();
    const rangeMs = Math.max(toMs - fromMs, 1);

    metrics.forEach((metric) => {
        const samples = (metric.samples || [])
                .sort((left, right) => new Date(left.measuredAt) - new Date(right.measuredAt));
        const valueSamples = samples.filter((sample) => sample.successful && Number.isFinite(sample.value));
        const errorSamples = samples.filter((sample) => !sample.successful || !Number.isFinite(sample.value));

        const card = document.createElement('article');
        card.className = 'metric-card';

        const header = document.createElement('div');
        header.className = 'metric-card-header';

        const titleGroup = document.createElement('div');
        const title = document.createElement('h3');
        title.textContent = metric.name;
        const meta = document.createElement('p');
        meta.className = 'subtitle';
        meta.textContent = metric.unit ? `Unit: ${metric.unit}` : 'No unit';
        titleGroup.append(title, meta);

        const latest = valueSamples.at(-1);
        const latestValue = document.createElement('div');
        latestValue.className = 'metric-latest';
        latestValue.textContent = latest ? formatMetricValue(latest.value, metric.unit) : 'N/A';
        header.append(titleGroup, latestValue);

        const chart = document.createElement('div');
        chart.className = 'metric-chart';
        chart.setAttribute('role', 'img');
        chart.setAttribute('aria-label', `${metric.name} metric chart`);

        if (samples.length === 0) {
            const empty = document.createElement('span');
            empty.className = 'metric-chart-empty';
            empty.textContent = 'No samples in range';
            chart.append(empty);
        } else {
            renderMetricChart(chart, valueSamples, errorSamples, metric, fromMs, toMs, rangeMs);
        }

        const footer = document.createElement('div');
        footer.className = 'metric-footer';
        footer.innerHTML = `
            <span>${valueSamples.length} values</span>
            <span>${errorSamples.length} errors</span>`;

        card.append(header, chart, footer);
        metricsGraph.append(card);
    });
}

function renderMetricChart(chart, valueSamples, errorSamples, metric, fromMs, toMs, rangeMs) {
    if (valueSamples.length === 0) {
        const empty = document.createElement('span');
        empty.className = 'metric-chart-empty';
        empty.textContent = 'No numeric samples in range';
        chart.append(empty);
        return;
    }

    const values = valueSamples.map((sample) => sample.value);
    const actualMinValue = Math.min(...values);
    const actualMaxValue = Math.max(...values);
    let chartMinValue = actualMinValue;
    let chartMaxValue = actualMaxValue;
    const constantValue = actualMinValue === actualMaxValue;
    if (constantValue) {
        const padding = Math.max(Math.abs(actualMinValue) * 0.1, 1);
        chartMinValue -= padding;
        chartMaxValue += padding;
    }
    const valueRange = Math.max(chartMaxValue - chartMinValue, 1);

    const lineSamples = downsampleSamples(valueSamples, 500);
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.classList.add('metric-line-svg');
    svg.setAttribute('viewBox', '0 0 100 100');
    svg.setAttribute('preserveAspectRatio', 'none');

    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.classList.add('metric-line');
    path.setAttribute('d', lineSamples.map((sample, index) => {
        const point = metricPoint(sample, fromMs, rangeMs, chartMinValue, valueRange);
        return `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(3)} ${point.y.toFixed(3)}`;
    }).join(' '));
    svg.append(path);
    chart.append(svg);

    const topLabel = document.createElement('span');
    topLabel.className = 'metric-axis-label top';
    topLabel.textContent = formatMetricValue(actualMaxValue, metric.unit);
    chart.append(topLabel);

    if (!constantValue) {
        const bottomLabel = document.createElement('span');
        bottomLabel.className = 'metric-axis-label bottom';
        bottomLabel.textContent = formatMetricValue(actualMinValue, metric.unit);
        chart.append(bottomLabel);
    }
}

function downsampleSamples(samples, limit) {
    if (samples.length <= limit) {
        return samples;
    }

    const selected = [];
    const step = (samples.length - 1) / (limit - 1);
    let lastIndex = -1;
    for (let i = 0; i < limit; i += 1) {
        const index = Math.min(Math.round(i * step), samples.length - 1);
        if (index !== lastIndex) {
            selected.push(samples[index]);
            lastIndex = index;
        }
    }
    return selected;
}

function metricPoint(sample, fromMs, rangeMs, minValue, valueRange) {
    const measuredAtMs = new Date(sample.measuredAt).getTime();
    const x = clamp(((measuredAtMs - fromMs) / rangeMs) * 100, 0, 100);
    const normalized = (sample.value - minValue) / valueRange;
    const y = clamp(90 - normalized * 78, 8, 92);
    return {x, y};
}

function buildVisibleStatusRuns(samples, fromMs, toMs) {
    const runs = buildStatusRuns(samples, toMs)
            .map((run) => ({
                ...run,
                renderStartMs: run.startMs,
                renderEndMs: run.endMs,
            }))
            .filter((run) => run.renderEndMs > fromMs && run.renderStartMs < toMs);

    if (runs.length > 0) {
        runs[runs.length - 1].renderEndMs = toMs;
    }

    return runs;
}

function buildStatusRuns(samples, toMs) {
    const runs = [];
    let currentRun = null;

    samples.forEach((sample, index) => {
        const checkedAtMs = new Date(sample.checkedAt).getTime();
        const nextMs = index + 1 < samples.length
                ? new Date(samples[index + 1].checkedAt).getTime()
                : toMs;

        if (!currentRun || currentRun.online !== sample.online) {
            if (currentRun) {
                runs.push(currentRun);
            }
            currentRun = {
                online: sample.online,
                startMs: checkedAtMs,
                endMs: nextMs,
                samples: [sample],
            };
        } else {
            currentRun.endMs = nextMs;
            currentRun.samples.push(sample);
        }
    });

    if (currentRun) {
        runs.push(currentRun);
    }

    return runs;
}

function sampleForSegmentClick(event, segment, run) {
    const rect = segment.getBoundingClientRect();
    const ratio = rect.width <= 0 ? 0 : clamp((event.clientX - rect.left) / rect.width, 0, 1);
    const clickedAtMs = run.startMs + (run.endMs - run.startMs) * ratio;
    const nearestSample = run.samples.reduce((nearest, sample) => {
        const nearestDistance = Math.abs(new Date(nearest.checkedAt).getTime() - clickedAtMs);
        const sampleDistance = Math.abs(new Date(sample.checkedAt).getTime() - clickedAtMs);
        return sampleDistance < nearestDistance ? sample : nearest;
    }, run.samples[0]);
    return sampleAt(nearestSample, clickedAtMs);
}

function sampleAt(sample, clickedAtMs) {
    if (!sample || sample.online) {
        return sample;
    }

    const clickedAt = new Date(clickedAtMs).toISOString();
    return {
        ...sample,
        checkedAt: clickedAt,
        failedChecks: (sample.failedChecks || []).map((check) => updateFailureDuration(check, clickedAtMs, clickedAt)),
    };
}

function updateFailureDuration(check, clickedAtMs, clickedAt) {
    if (!check.failureStartedAt) {
        return check;
    }

    const failureStartedAtMs = new Date(check.failureStartedAt).getTime();
    if (Number.isNaN(failureStartedAtMs) || clickedAtMs < failureStartedAtMs) {
        return check;
    }

    return {
        ...check,
        checkedAt: clickedAt,
        failureEndedAt: clickedAt,
        failureDurationMs: Math.max(Math.round(clickedAtMs - failureStartedAtMs), 0),
    };
}

function showFailureDialog(resource, sample) {
    const failedChecks = sample.failedChecks || [];
    failureTitle.textContent = resource.resourceName;
    failureSubtitle.textContent = `${formatDateTime(sample.checkedAt)} · ${failedChecks.length} failed checks`;
    failureList.replaceChildren();

    if (failedChecks.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'subtitle';
        empty.textContent = 'No failed check details were recorded for this sample.';
        failureList.append(empty);
    } else {
        failedChecks.forEach((check) => {
            const item = document.createElement('article');
            item.className = 'failure-item';
            item.innerHTML = `
                <div class="failure-item-header">
                    <span class="status-dot offline"></span>
                    <strong>${escapeHtml(check.checkName)} (${escapeHtml(check.checkType)})</strong>
                </div>
                <dl>
                    <dt>Target</dt><dd>${escapeHtml(check.target || 'N/A')}</dd>
                    <dt>Checked at</dt><dd>${formatDateTime(check.checkedAt)}</dd>
                    <dt>Duration</dt><dd>${escapeHtml(failurePeriodText(check))}</dd>
                    ${check.errorMessage ? `<dt>Error</dt><dd>${escapeHtml(check.errorMessage)}</dd>` : ''}
                    ${check.details ? `<dt>Details</dt><dd>${escapeHtml(check.details)}</dd>` : ''}
                </dl>`;
            failureList.append(item);
        });
    }

    failureDialog.showModal();
}

function failurePeriodText(check) {
    if (!check.failureStartedAt || !check.failureEndedAt) {
        return 'N/A';
    }
    if ((Number(check.failureDurationMs) || 0) <= 0 || check.failureStartedAt === check.failureEndedAt) {
        return '-';
    }
    return `${formatDuration(check.failureDurationMs || 0)} (${formatDateTime(check.failureStartedAt)} → ${formatDateTime(check.failureEndedAt)})`;
}

function showMessage(text, error) {
    message.hidden = !text;
    message.textContent = text;
    message.style.borderColor = error ? 'var(--red)' : 'var(--border)';
}

function formatPercent(value) {
    return value === null || value === undefined ? 'N/A' : `${value.toFixed(2)}%`;
}

function formatMetricValue(value, unit) {
    if (!Number.isFinite(value)) {
        return 'N/A';
    }
    const absolute = Math.abs(value);
    const formatted = absolute >= 100
            ? value.toFixed(0)
            : absolute >= 10
                    ? value.toFixed(1)
                    : value.toFixed(2);
    return unit ? `${formatted} ${unit}` : formatted;
}

function formatDateTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return '';
    }
    return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function formatDateTimeMinute(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return '';
    }
    return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return '';
    }
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function pad(value) {
    return String(value).padStart(2, '0');
}

function formatDuration(durationMs) {
    if (durationMs < 1000) {
        return `${durationMs} ms`;
    }
    const seconds = durationMs / 1000;
    if (seconds < 60) {
        return `${seconds.toFixed(seconds < 10 ? 2 : 1)} s`;
    }
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.round(seconds % 60);
    return `${minutes}m ${remainingSeconds}s`;
}

function midpoint(fromMs, toMs) {
    return new Date(fromMs + (toMs - fromMs) / 2).toISOString();
}

function statusClass(result) {
    if (!result || result.successful === null || result.successful === undefined) {
        return 'unknown';
    }
    return result.successful ? 'online' : 'offline';
}

function statusText(result) {
    if (!result || result.successful === null || result.successful === undefined) {
        return 'No data';
    }
    return result.successful ? 'Online' : 'Offline';
}

function latestResourceStatus(item) {
    return item.online === null || item.online === undefined ? null : {successful: item.online};
}

function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
}

function escapeHtml(value) {
    const div = document.createElement('div');
    div.textContent = value ?? '';
    return div.innerHTML;
}

function readCustomRange() {
    const from = parseDateTimeInput(fromInput.value);
    const to = parseDateTimeInput(toInput.value);
    setDateTimeInputValidity(fromInput, from);
    setDateTimeInputValidity(toInput, to);

    if (!from || !to) {
        showMessage('Use date-time format YYYY.MM.DD HH:mm, for example 2026.04.30 16:58.', true);
        (from ? toInput : fromInput).reportValidity();
        return null;
    }
    if (to <= from) {
        toInput.setCustomValidity('End date-time must be later than start date-time.');
        toInput.reportValidity();
        showMessage('End date-time must be later than start date-time.', true);
        return null;
    }

    return {from, to};
}

function updateRangeInputs(from, to, clearValidity) {
    fromInput.value = formatDateTimeInput(from);
    toInput.value = formatDateTimeInput(to);
    if (clearValidity) {
        fromInput.setCustomValidity('');
        toInput.setCustomValidity('');
    }
}

function isCustomRangeEditing() {
    return state.rangeInputsDirty || document.activeElement === fromInput || document.activeElement === toInput;
}

function parseDateTimeInput(value) {
    const trimmed = value.trim();
    const match = trimmed.match(/^(\d{4})\.(\d{2})\.(\d{2})\s+(\d{2}):(\d{2})$/);
    if (!match) {
        return null;
    }

    const [, yearText, monthText, dayText, hourText, minuteText] = match;
    const year = Number(yearText);
    const month = Number(monthText);
    const day = Number(dayText);
    const hour = Number(hourText);
    const minute = Number(minuteText);
    const date = new Date(year, month - 1, day, hour, minute, 0, 0);

    if (date.getFullYear() !== year
            || date.getMonth() !== month - 1
            || date.getDate() !== day
            || date.getHours() !== hour
            || date.getMinutes() !== minute) {
        return null;
    }

    return date;
}

function setDateTimeInputValidity(input, date) {
    input.setCustomValidity(date ? '' : 'Use format YYYY.MM.DD HH:mm, for example 2026.04.30 16:58.');
}

function formatDateTimeInput(value) {
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) {
        return '';
    }
    return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
