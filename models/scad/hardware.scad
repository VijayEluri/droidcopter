axle_r = 4.5;
axle_separation = 30;

module axle(h) {
	rotate(v=[-1,0,0], a=90) cylinder(r=axle_r, h=h, $fn=10);
}

module axlepair(h=100) {
	union() {
		axle(h);
		translate(v=[axle_separation, 0, 0]) axle(h);
	}
}

module m2(h=16) {
	union() {
		cylinder(r=2, h=2);
		cylinder(r=1.5, h=h);
	}
}

module m3(h=16) {
	union() {
		cylinder(r=3.25, h=3);
		cylinder(r=2, h=h);
	}
}

module m5(h=16) {
	union() {
		cylinder(r=4.5, h=5);
		cylinder(r=3, h=h);
	}
}