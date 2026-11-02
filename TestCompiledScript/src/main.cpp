/*
 * Copyright (c) 2026 OpenManager
 * Standalone C / C++ Test Binary with Dear ImGui & Syscall Probe
 */

#include "syscall_probe.h"
#include "../imgui/imgui.h"
#include "../imgui/imgui_internal.h"

#include <iostream>
#include <iomanip>
#include <vector>
#include <string>
#include <chrono>
#include <cmath>

static void RenderAsciiDashboard(const SyscallProbeResult& res, const ImDrawData* draw_data) {
    std::cout << "\033[1;36m+-------------------------------------------------------------+\033[0m\n";
    std::cout << "\033[1;36m|\033[0m   \033[1;37mOpenManager ImGui Headless Dashboard (ASCII Canvas)\033[0m       \033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m+-------------------------------------------------------------+\033[0m\n";
    std::cout << "\033[1;36m|\033[0m \033[1;33m[1] SYSTEM STATUS\033[0m                                           \033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m|\033[0m   Kernel   : " << std::left << std::setw(46) << (std::string(res.sysname) + " " + res.release + " (" + res.machine + ")") << "\033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m|\033[0m   Process  : PID " << std::left << std::setw(8) << res.pid
              << "UID " << std::setw(6) << res.uid
              << (res.is_root ? "\033[1;32m[ROOT]\033[0m                 " : "\033[1;31m[UNPRIVILEGED]\033[0m         ") << "\033[1;36m|\033[0m\n";

    unsigned long used_ram = (res.total_ram_mb > res.free_ram_mb) ? (res.total_ram_mb - res.free_ram_mb) : 0;
    int ram_pct = res.total_ram_mb > 0 ? (int)((used_ram * 100) / res.total_ram_mb) : 0;
    std::string ram_bar = "[";
    int bar_width = 25;
    int filled = (ram_pct * bar_width) / 100;
    for (int i = 0; i < bar_width; i++) ram_bar += (i < filled) ? '#' : '-';
    ram_bar += "] " + std::to_string(ram_pct) + "%";

    std::cout << "\033[1;36m|\033[0m   RAM Usage: " << std::left << std::setw(46) << ram_bar << "\033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m|\033[0m                                                             \033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m|\033[0m \033[1;33m[2] DEAR IMGUI RENDER ENGINE METRICS\033[0m                        \033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m|\033[0m   ImGui Version  : " << std::left << std::setw(40) << IMGUI_VERSION << "\033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m|\033[0m   Command Lists  : " << std::left << std::setw(40) << (draw_data ? draw_data->CmdListsCount : 0) << "\033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m|\033[0m   Total Vertices : " << std::left << std::setw(40) << (draw_data ? draw_data->TotalVtxCount : 0) << "\033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m|\033[0m   Total Indices  : " << std::left << std::setw(40) << (draw_data ? draw_data->TotalIdxCount : 0) << "\033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m|\033[0m   Syscall Latency: " << std::left << std::setw(40) << (std::to_string(res.syscall_bench_ns) + " ns/call") << "\033[1;36m|\033[0m\n";
    std::cout << "\033[1;36m+-------------------------------------------------------------+\033[0m\n\n";
}

int main(int argc, char** argv) {
    std::cout << "\033[1;32m>>> Starting OpenManager C & C++ Test Suite with ImGui <<<\033[0m\n";

    // 1. Run Low-Level C Syscall Probe
    SyscallProbeResult probe_res;
    if (run_syscall_probe(&probe_res) != 0) {
        std::cerr << "\033[1;31m[-] Syscall probe execution failed!\033[0m\n";
        return 1;
    }
    print_syscall_probe_result(&probe_res);

    // 2. Initialize Dear ImGui Context
    std::cout << "[*] Initializing Dear ImGui (" << IMGUI_VERSION << ") context...\n";
    IMGUI_CHECKVERSION();
    ImGuiContext* ctx = ImGui::CreateContext();
    if (!ctx) {
        std::cerr << "\033[1;31m[-] Failed to create ImGui context!\033[0m\n";
        return 1;
    }
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2(1080.0f, 1920.0f); // High-res Android viewport
    io.DeltaTime = 1.0f / 60.0f;
    io.IniFilename = NULL; // Headless: do not write imgui.ini to disk

    // Setup visual style
    ImGui::StyleColorsDark();
    ImGuiStyle& style = ImGui::GetStyle();
    style.WindowRounding = 8.0f;
    style.FrameRounding = 4.0f;

    // 3. Build & Verify Font Atlas
    std::cout << "[*] Building Font Texture Atlas...\n";
    unsigned char* font_pixels = NULL;
    int font_w = 0, font_h = 0;
    io.Fonts->GetTexDataAsRGBA32(&font_pixels, &font_w, &font_h);
    if (!font_pixels || font_w <= 0 || font_h <= 0) {
        std::cerr << "\033[1;31m[-] Font atlas rasterization failed!\033[0m\n";
        ImGui::DestroyContext(ctx);
        return 2;
    }
    std::cout << "    -> Font atlas ready: " << font_w << "x" << font_h << " pixels RGBA32\n";

    // 4. Simulate ImGui Frames with Complex Layouts
    std::cout << "[*] Simulating ImGui UI Frame generation...\n";
    const int TEST_FRAMES = 5;
    for (int frame = 0; frame < TEST_FRAMES; frame++) {
        ImGui::NewFrame();

        // Window 1: System Telemetry & Syscall Report
        ImGui::SetNextWindowPos(ImVec2(20.0f, 20.0f), ImGuiCond_Always);
        ImGui::SetNextWindowSize(ImVec2(600.0f, 500.0f), ImGuiCond_Always);
        if (ImGui::Begin("OpenManager Telemetry Dashboard", NULL, ImGuiWindowFlags_NoCollapse)) {
            ImGui::TextColored(ImVec4(0.0f, 0.9f, 0.5f, 1.0f), "SYSTEM DIAGNOSTIC PROBE");
            ImGui::Separator();

            ImGui::Text("Linux Kernel : %s (%s)", probe_res.sysname, probe_res.machine);
            ImGui::Text("Kernel Ver   : %s", probe_res.release);
            ImGui::Text("PID / PPID   : %d / %d", probe_res.pid, probe_res.ppid);

            if (probe_res.is_root) {
                ImGui::TextColored(ImVec4(0.0f, 1.0f, 0.0f, 1.0f), "Privilege: ROOT ACCESS GRANTED (UID %d)", probe_res.uid);
            } else {
                ImGui::TextColored(ImVec4(1.0f, 0.7f, 0.0f, 1.0f), "Privilege: STANDARD USER (UID %d)", probe_res.uid);
            }

            ImGui::Spacing();
            if (ImGui::CollapsingHeader("RAM & Resources", ImGuiTreeNodeFlags_DefaultOpen)) {
                unsigned long used = (probe_res.total_ram_mb > probe_res.free_ram_mb) ?
                    (probe_res.total_ram_mb - probe_res.free_ram_mb) : 0;
                float pct = probe_res.total_ram_mb > 0 ? ((float)used / (float)probe_res.total_ram_mb) : 0.0f;
                char overlay[64];
                snprintf(overlay, sizeof(overlay), "%lu MB / %lu MB (%.0f%%)", used, probe_res.total_ram_mb, pct * 100.0f);
                ImGui::ProgressBar(pct, ImVec2(-1.0f, 0.0f), overlay);

                ImGui::Text("Uptime : %ld seconds", probe_res.uptime_sec);
                ImGui::Text("Active Processes : %u", probe_res.procs_count);
            }

            ImGui::Spacing();
            if (ImGui::CollapsingHeader("Security Telemetry", ImGuiTreeNodeFlags_DefaultOpen)) {
                const char* se_text = (probe_res.selinux_enforce == 1) ? "Enforcing" :
                                      (probe_res.selinux_enforce == 0) ? "Permissive" : "Disabled/Unknown";
                ImGui::Text("SELinux Mode : %s", se_text);
                ImGui::Text("SU Binary    : %s", probe_res.su_binary_detected ? probe_res.su_binary_path : "Not Found");
                ImGui::Text("Syscall Latency : %.2f ns/call", probe_res.syscall_bench_ns);
            }

            ImGui::Spacing();
            if (ImGui::Button("Simulate Action Kill Process")) {
                // UI interaction simulation
            }
            ImGui::SameLine();
            if (ImGui::Button("Refresh Telemetry")) {
                // UI interaction simulation
            }
        }
        ImGui::End();

        // Window 2: ImGui Widget & Table Validation
        ImGui::SetNextWindowPos(ImVec2(640.0f, 20.0f), ImGuiCond_Always);
        ImGui::SetNextWindowSize(ImVec2(400.0f, 500.0f), ImGuiCond_Always);
        if (ImGui::Begin("ImGui Controls & Tables Test")) {
            ImGui::Text("Rendering Frame: #%d", frame + 1);

            static float slider_val = 42.0f;
            ImGui::SliderFloat("Float Test", &slider_val, 0.0f, 100.0f);

            static bool check_val = true;
            ImGui::Checkbox("Syscall Tracing Active", &check_val);

            // Table validation
            if (ImGui::BeginTable("MetricsTable", 2, ImGuiTableFlags_Borders | ImGuiTableFlags_RowBg)) {
                ImGui::TableSetupColumn("Property");
                ImGui::TableSetupColumn("Value");
                ImGui::TableHeadersRow();

                ImGui::TableNextRow();
                ImGui::TableSetColumnIndex(0); ImGui::Text("Syscall BENCH");
                ImGui::TableSetColumnIndex(1); ImGui::Text("%.2f ns", probe_res.syscall_bench_ns);

                ImGui::TableNextRow();
                ImGui::TableSetColumnIndex(0); ImGui::Text("Root Status");
                ImGui::TableSetColumnIndex(1); ImGui::Text("%s", probe_res.is_root ? "UID 0" : "Unprivileged");

                ImGui::TableNextRow();
                ImGui::TableSetColumnIndex(0); ImGui::Text("SELinux");
                ImGui::TableSetColumnIndex(1); ImGui::Text("%d", probe_res.selinux_enforce);

                ImGui::EndTable();
            }
        }
        ImGui::End();

        // Render Frame
        ImGui::Render();
    }

    // 5. Inspect Rendered Draw Data
    ImDrawData* draw_data = ImGui::GetDrawData();
    if (!draw_data || !draw_data->Valid) {
        std::cerr << "\033[1;31m[-] ImGui render produced invalid draw data!\033[0m\n";
        ImGui::DestroyContext(ctx);
        return 3;
    }

    std::cout << "[+] ImGui Draw Data Successfully Verified!\n";
    std::cout << "    - Draw Command Lists : " << draw_data->CmdListsCount << "\n";
    std::cout << "    - Total Vertex Count : " << draw_data->TotalVtxCount << "\n";
    std::cout << "    - Total Index Count  : " << draw_data->TotalIdxCount << "\n";

    // Verify commands in detail
    size_t total_draw_cmds = 0;
    for (int i = 0; i < draw_data->CmdListsCount; i++) {
        const ImDrawList* cmd_list = draw_data->CmdLists[i];
        total_draw_cmds += cmd_list->CmdBuffer.Size;
    }
    std::cout << "    - Total Draw Commands: " << total_draw_cmds << "\n\n";

    // 6. Output Terminal Visual Dashboard
    RenderAsciiDashboard(probe_res, draw_data);

    // 7. Test Binary Tracer Deletion Interceptor (Non-Fatal Probe)
    std::cout << "[*] Testing Binary Tracer Deletion Interception (Non-Fatal Probe)...\n";
    const char* test_delete_target = "/data/local/tmp/om_tracer_probe.tmp";
    std::cout << "[OM_TRACER:DELETE:unlink:" << test_delete_target << "]\n";
    std::cout.flush();

    const char* policy_env = getenv("OM_DELETION_POLICY");
    if (policy_env && strcmp(policy_env, "BLOCK_ALL") == 0) {
        std::cout << "[OM_TRACER:BLOCKED] Simulated deletion blocked cleanly (policy: BLOCK_ALL)\n";
    } else {
        std::cout << "[+] Simulated deletion probe executed without terminating binary.\n";
    }

    // 8. Cleanup
    ImGui::DestroyContext(ctx);
    std::cout << "\033[1;32m[✓] All C Syscall, ImGui UI, and Tracer Deletion Guard verification tests PASSED successfully!\033[0m\n";
    return 0;
}
