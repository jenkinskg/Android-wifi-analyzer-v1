from pathlib import Path

root = Path("pings-work/Pings-Android-v0.1")
main = root / "app/src/main/java/com/keith/pings/MainActivity.java"
build = root / "app/build.gradle.kts"

s = main.read_text()

s = s.replace(
    'Button add = button("ADD SUBNET"); Button current = button("ADD CURRENT /24");',
    'Button add = button("ADD SUBNET"); Button current = button("ADD CURRENT");'
)

s = s.replace(
    'String cidr = SubnetUtil.currentIpv4Slash24();',
    'String cidr = CurrentSubnetUtil.detect(this);'
)

old = '''    private void showAddSubnet(String prefill) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = edit("Name, e.g. Yonkers APs"); EditText cidr = edit("CIDR, e.g. 10.218.150.0/24");
        if (prefill != null) cidr.setText(prefill); form.addView(name); form.addView(cidr);
        new AlertDialog.Builder(this).setTitle("Add subnet").setView(form)
                .setPositiveButton("Save", (d,w) -> {
                    try {
                        String n = name.getText().toString().trim(); if (n.isEmpty()) n = "Subnet";
                        store.addProfile(n, cidr.getText().toString()); renderSubnets();
                    } catch (Exception e) { toast(e.getMessage()); }
                }).setNegativeButton("Cancel", null).show();
    }
'''

new = '''    private void showAddSubnet(String prefill) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = edit("Name, e.g. Yonkers APs");
        EditText cidr = edit("CIDR, e.g. 10.218.150.0/24");
        TextView choice = tv("Enter a subnet manually or use the phone's current Wi-Fi/Ethernet subnet.", 13, c("#9FB2C3"));
        choice.setPadding(0, 0, 0, dp(6));
        Button useCurrent = button("USE CURRENT SUBNET");
        useCurrent.setOnClickListener(v -> {
            String detected = CurrentSubnetUtil.detect(this);
            if (detected == null) {
                toast("Could not detect a private IPv4 Wi-Fi/Ethernet subnet");
                return;
            }
            cidr.setText(detected);
            if (name.getText().toString().trim().isEmpty()) name.setText("Current LAN");
            toast("Detected " + detected);
        });
        if (prefill != null) {
            cidr.setText(prefill);
            name.setText("Current LAN");
        }
        form.addView(choice);
        form.addView(useCurrent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        form.addView(name);
        form.addView(cidr);
        new AlertDialog.Builder(this).setTitle("Add subnet").setView(form)
                .setPositiveButton("Save", (d,w) -> {
                    try {
                        String n = name.getText().toString().trim(); if (n.isEmpty()) n = "Subnet";
                        store.addProfile(n, cidr.getText().toString()); renderSubnets();
                    } catch (Exception e) { toast(e.getMessage()); }
                }).setNegativeButton("Cancel", null).show();
    }
'''

if old not in s:
    raise SystemExit("showAddSubnet block not found")

s = s.replace(old, new)
main.write_text(s)

b = build.read_text()
b = b.replace('versionCode = 1', 'versionCode = 2')
b = b.replace('versionName = "0.1.0"', 'versionName = "0.2.0"')
build.write_text(b)
